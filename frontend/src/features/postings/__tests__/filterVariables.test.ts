import { describe, expect, it } from "vitest";
import { toPostingFilterInput } from "../filterVariables";
import type { JobSearchState } from "../filterSchema";

describe("job search GraphQL variables", () => {
  it("maps positive constraints into all and exclusions into not children", () => {
    const state: JobSearchState = {
      text: "engineer",
      skills: [
        { slug: "go", level: "REQUIRED", exclude: false },
        { slug: "react", level: "PREFERRED", exclude: true },
        { slug: "kotlin", level: "MENTIONED", exclude: false },
      ],
      role: "BACKEND",
      employment: "FULL_TIME",
      remote: "HYBRID",
      siteId: "opaque:42",
      updatedWithin: "7d",
      order: "UPDATED_DESC",
    };
    expect(toPostingFilterInput(state, new Date("2026-09-24T12:00:00.000Z"))).toEqual({
      filter: { all: [
        { hasStatus: "OPEN" },
        { textContains: "engineer" }, { atSite: "opaque:42" },
        { hasRole: "BACKEND" }, { hasEmployment: "FULL_TIME" }, { hasRemotePolicy: "HYBRID" },
        { updatedAfter: "2026-09-17T12:00:00.000Z" },
        { hasSkill: { slug: "go", level: "REQUIRED" } },
        { hasSkill: { slug: "kotlin", level: "MENTIONED" } },
        { not: { hasSkill: { slug: "react", level: "PREFERRED" } } },
      ] },
      orderBy: "UPDATED_DESC",
    });
  });

  it("keeps the public jobs query open-only even without visible filters", () => {
    expect(toPostingFilterInput({ skills: [] })).toEqual({ filter: { all: [{ hasStatus: "OPEN" }] } });
    expect(toPostingFilterInput({ skills: [{ slug: "go", exclude: false }] })).toEqual({ filter: { all: [{ hasStatus: "OPEN" }, { hasSkill: { slug: "go" } }] } });
  });

  it.each([
    [{ text: "engineer" }, { textContains: "engineer" }],
    [{ siteId: "opaque:42" }, { atSite: "opaque:42" }],
    [{ role: "DATA" }, { hasRole: "DATA" }],
    [{ employment: "CONTRACT" }, { hasEmployment: "CONTRACT" }],
    [{ remote: "REMOTE" }, { hasRemotePolicy: "REMOTE" }],
    [{ skills: [{ slug: "go", level: "REQUIRED", exclude: false }] }, { hasSkill: { slug: "go", level: "REQUIRED" } }],
    [{ skills: [{ slug: "go", level: "PREFERRED", exclude: false }] }, { hasSkill: { slug: "go", level: "PREFERRED" } }],
    [{ skills: [{ slug: "go", level: "MENTIONED", exclude: true }] }, { not: { hasSkill: { slug: "go", level: "MENTIONED" } } }],
  ] as const)("retains OPEN status with a selected %j filter", (selection, constraint) => {
    expect(toPostingFilterInput({ skills: [], ...selection } as JobSearchState)).toEqual({ filter: { all: [{ hasStatus: "OPEN" }, constraint] } });
  });

  it.each([["24h", "2026-09-23T12:00:00.000Z"], ["30d", "2026-08-25T12:00:00.000Z"]] as const)("uses supplied time for %s", (window, expected) => {
    expect(toPostingFilterInput({ skills: [], updatedWithin: window }, new Date("2026-09-24T12:00:00.000Z"))).toEqual({ filter: { all: [{ hasStatus: "OPEN" }, { updatedAfter: expected }] } });
  });

  it("requires an explicit clock for a relative update window", () => {
    expect(() => toPostingFilterInput({ skills: [], updatedWithin: "7d" })).toThrow(/now/i);
  });
});
