import type { JobSearchState, JobSkillFilter, ParseResult, UpdateWindow } from "./filterSchema";

const roles = {
  backend: "BACKEND", frontend: "FRONTEND", fullstack: "FULLSTACK", mobile: "MOBILE",
  data: "DATA", devops: "DEVOPS", design: "DESIGN", product: "PRODUCT",
  qa: "QA", unknown: "UNKNOWN",
} as const;
const employments = {
  "full-time": "FULL_TIME", "part-time": "PART_TIME", contract: "CONTRACT",
  internship: "INTERNSHIP", unknown: "UNKNOWN",
} as const;
const remotes = { remote: "REMOTE", hybrid: "HYBRID", onsite: "ONSITE", unknown: "UNKNOWN" } as const;
const levels = { required: "REQUIRED", preferred: "PREFERRED", mentioned: "MENTIONED" } as const;
const orders = { "updated-desc": "UPDATED_DESC" } as const;
const windows = new Set<UpdateWindow>(["24h", "7d", "30d"]);
const knownKeys = new Set(["q", "skill", "role", "employment", "remote", "site", "updated", "order"]);

function enumValue<T extends string>(input: string, choices: Record<string, T>): T | undefined {
  return Object.prototype.hasOwnProperty.call(choices, input) ? choices[input] : undefined;
}

function enumKey<T extends string>(value: T, choices: Record<string, T>): string | undefined {
  return Object.keys(choices).find((key) => choices[key] === value);
}

function parseSkill(raw: string): JobSkillFilter | undefined {
  const match = /^(-?)([^:]+?)(?::(required|preferred|mentioned))?$/.exec(raw.trim());
  if (!match) return undefined;
  const slug = match[2].trim();
  if (!slug || /\s/.test(slug) || slug.startsWith("-")) return undefined;
  return {
    slug,
    ...(match[3] ? { level: levels[match[3] as keyof typeof levels] } : {}),
    exclude: match[1] === "-",
  };
}

function compareSkills(a: JobSkillFilter, b: JobSkillFilter): number {
  return Number(a.exclude) - Number(b.exclude) ||
    (a.slug < b.slug ? -1 : a.slug > b.slug ? 1 : 0) ||
    ((a.level ?? "") < (b.level ?? "") ? -1 : (a.level ?? "") > (b.level ?? "") ? 1 : 0);
}

export function parseJobSearch(search: string): ParseResult {
  const params = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  const state: JobSearchState = { skills: [] };
  const corrections: string[] = [];
  const seen = new Set<string>();
  const skills = new Set<string>();

  for (const [key, value] of params) {
    if (!knownKeys.has(key)) {
      corrections.push(`Removed unknown search parameter “${key}”.`);
      continue;
    }
    if (key === "skill") {
      const skill = parseSkill(value);
      if (!skill) {
        corrections.push("Removed an invalid skill filter.");
        continue;
      }
      const identity = `${Number(skill.exclude)}\0${skill.slug}\0${skill.level ?? ""}`;
      if (skills.has(identity)) {
        corrections.push(`Removed a duplicate skill filter for “${skill.slug}”.`);
        continue;
      }
      skills.add(identity);
      state.skills.push(skill);
      continue;
    }
    if (key === "q" || key === "site") {
      const trimmed = value.trim();
      if (!trimmed) {
        corrections.push(`Removed an empty ${key === "q" ? "text" : "site"} filter.`);
        continue;
      }
      if (seen.has(key)) {
        corrections.push(`Removed a duplicate ${key} filter.`);
        continue;
      }
      seen.add(key);
      if (key === "q") state.text = trimmed;
      else state.siteId = trimmed;
      continue;
    }
    const parsed = key === "role" ? enumValue(value, roles)
      : key === "employment" ? enumValue(value, employments)
      : key === "remote" ? enumValue(value, remotes)
      : key === "order" ? enumValue(value, orders)
      : key === "updated" && windows.has(value as UpdateWindow) ? value : undefined;
    if (!parsed) {
      corrections.push(`Removed an invalid ${key} filter.`);
      continue;
    }
    if (seen.has(key)) {
      corrections.push(`Removed a duplicate ${key} filter.`);
      continue;
    }
    seen.add(key);
    if (key === "role") state.role = parsed as JobSearchState["role"];
    else if (key === "employment") state.employment = parsed as JobSearchState["employment"];
    else if (key === "remote") state.remote = parsed as JobSearchState["remote"];
    else if (key === "order") state.order = parsed as JobSearchState["order"];
    else if (key === "updated") state.updatedWithin = parsed as UpdateWindow;
  }

  state.skills.sort(compareSkills);
  const canonicalSearch = serializeJobSearch(state);
  const inputSearch = search ? (search.startsWith("?") ? search : `?${search}`) : "";
  if (inputSearch !== canonicalSearch && corrections.length === 0) {
    corrections.push("Search filters were reordered or normalized.");
  }
  return { state, canonicalSearch, corrections };
}

export function serializeJobSearch(state: JobSearchState): string {
  const params = new URLSearchParams();
  if (state.text?.trim()) params.set("q", state.text.trim());
  const seenSkills = new Set<string>();
  const skills = state.skills.flatMap((skill) => {
    const parsed = parseSkill(`${skill.exclude ? "-" : ""}${skill.slug}${skill.level ? `:${skill.level.toLowerCase()}` : ""}`);
    if (!parsed) return [];
    const identity = `${Number(parsed.exclude)}\0${parsed.slug}\0${parsed.level ?? ""}`;
    if (seenSkills.has(identity)) return [];
    seenSkills.add(identity);
    return [parsed];
  });
  for (const skill of skills.sort(compareSkills)) {
    params.append("skill", `${skill.exclude ? "-" : ""}${skill.slug}${skill.level ? `:${skill.level.toLowerCase()}` : ""}`);
  }
  const role = state.role && enumKey(state.role, roles);
  if (role) params.set("role", role);
  const employment = state.employment && enumKey(state.employment, employments);
  if (employment) params.set("employment", employment);
  const remote = state.remote && enumKey(state.remote, remotes);
  if (remote) params.set("remote", remote);
  if (state.siteId?.trim()) params.set("site", state.siteId.trim());
  if (state.updatedWithin && windows.has(state.updatedWithin)) params.set("updated", state.updatedWithin);
  const order = state.order && enumKey(state.order, orders);
  if (order) params.set("order", order);
  const body = params.toString();
  return body ? `?${body}` : "";
}
