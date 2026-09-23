import { describe, expect, it } from "vitest";
import { parseJobSearch, serializeJobSearch } from "../filterCodec";
import type { JobSearchState } from "../filterSchema";

describe("job search URL codec", () => {
  it.each([
    ["?q=%20Senior+%ED%95%9C%EA%B8%80%20", "?q=Senior+%ED%95%9C%EA%B8%80", "Senior 한글"],
    ["?role=backend&employment=full-time&remote=hybrid", "?role=backend&employment=full-time&remote=hybrid", undefined],
    ["?site=Q2FyZWVyU2l0ZTox", "?site=Q2FyZWVyU2l0ZTox", undefined],
    ["?updated=7d&order=updated-desc", "?updated=7d&order=updated-desc", undefined],
  ])("parses and canonicalizes %s", (input, canonical, text) => {
    const result = parseJobSearch(input);
    expect(result.canonicalSearch).toBe(canonical);
    if (text !== undefined) expect(result.state.text).toBe(text);
    expect(serializeJobSearch(result.state)).toBe(canonical);
  });

  it("orders keys and repeated skills deterministically", () => {
    const result = parseJobSearch("?remote=remote&skill=-python:preferred&q=hi&skill=react:required&skill=go&role=data&skill=go");
    expect(result.canonicalSearch).toBe("?q=hi&skill=go&skill=react%3Arequired&skill=-python%3Apreferred&role=data&remote=remote");
    expect(result.state.skills).toEqual([
      { slug: "go", exclude: false },
      { slug: "react", level: "REQUIRED", exclude: false },
      { slug: "python", level: "PREFERRED", exclude: true },
    ]);
    expect(result.corrections.length).toBeGreaterThan(0);
  });

  it("drops unknown keys, cursor, invalid enums, and malformed skills with announcements", () => {
    const result = parseJobSearch("?after=secret&x=1&role=wizard&employment=FULL_TIME&remote=space&updated=2y&order=oldest&skill=-&skill=rust:expert&q=%20");
    expect(result.state).toEqual({ skills: [] });
    expect(result.canonicalSearch).toBe("");
    expect(result.corrections.length).toBeGreaterThan(0);
    expect(result.corrections.every((message) => message.length > 0)).toBe(true);
  });

  it("keeps the first valid singleton and reports duplicates", () => {
    const result = parseJobSearch("?role=bad&role=frontend&role=backend&site=opaque%3Aid&site=other");
    expect(result.state.role).toBe("FRONTEND");
    expect(result.state.siteId).toBe("opaque:id");
    expect(result.canonicalSearch).toBe("?role=frontend&site=opaque%3Aid");
    expect(result.corrections.length).toBeGreaterThan(0);
  });

  it("preserves Unicode skill slugs and every requirement level", () => {
    const result = parseJobSearch("?skill=%ED%95%9C%EA%B8%80:mentioned&skill=-caf%C3%A9:required&skill=rust:preferred");
    expect(result.state.skills).toEqual([
      { slug: "rust", level: "PREFERRED", exclude: false },
      { slug: "한글", level: "MENTIONED", exclude: false },
      { slug: "café", level: "REQUIRED", exclude: true },
    ]);
    expect(parseJobSearch(result.canonicalSearch).state).toEqual(result.state);
  });

  it("serializes complete state without pagination data", () => {
    const state: JobSearchState = {
      text: "C++ / 한국",
      skills: [{ slug: "c++", level: "MENTIONED", exclude: false }],
      role: "BACKEND",
      employment: "CONTRACT",
      remote: "REMOTE",
      siteId: "CareerSite:1",
      updatedWithin: "24h",
      order: "UPDATED_DESC",
    };
    const search = serializeJobSearch({ ...state, after: "cursor" } as JobSearchState);
    expect(search).toBe("?q=C%2B%2B+%2F+%ED%95%9C%EA%B5%AD&skill=c%2B%2B%3Amentioned&role=backend&employment=contract&remote=remote&site=CareerSite%3A1&updated=24h&order=updated-desc");
    expect(parseJobSearch(search).state).toEqual(state);
  });

  it("deduplicates and validates skills when serializing state directly", () => {
    const state: JobSearchState = { skills: [
      { slug: "go", exclude: false },
      { slug: "go", exclude: false },
      { slug: "go", level: "REQUIRED", exclude: false },
      { slug: "go", exclude: true },
      { slug: "", exclude: false },
      { slug: "rust:expert", exclude: false },
    ] };
    const search = serializeJobSearch(state);
    expect(search).toBe("?skill=go&skill=go%3Arequired&skill=-go");
    expect(parseJobSearch(search).canonicalSearch).toBe(search);
  });

  it("is idempotent across a table of mixed search strings", () => {
    for (const input of ["", "?skill=b&skill=a&skill=-a", "?q=%E2%9C%93&unknown=x", "?remote=onsite&employment=internship", "?skill=go:required&skill=go:preferred"]) {
      const once = parseJobSearch(input).canonicalSearch;
      expect(parseJobSearch(once).canonicalSearch).toBe(once);
    }
  });
});
