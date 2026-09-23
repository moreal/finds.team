import type { DiscoveryOperationsJobsQuery$variables, PostingFilterInput } from "../../__generated__/DiscoveryOperationsJobsQuery.graphql";
import type { JobSearchState } from "./filterSchema";

export function toPostingFilterInput(state: JobSearchState, now?: Date): DiscoveryOperationsJobsQuery$variables {
  const all: PostingFilterInput[] = [];
  if (state.text) all.push({ textContains: state.text });
  if (state.siteId) all.push({ atSite: state.siteId });
  if (state.role) all.push({ hasRole: state.role });
  if (state.employment) all.push({ hasEmployment: state.employment });
  if (state.remote) all.push({ hasRemotePolicy: state.remote });
  if (state.updatedWithin) {
    if (!now || !Number.isFinite(now.getTime())) throw new Error("A valid explicit now is required for updatedWithin.");
    const duration = { "24h": 1, "7d": 7, "30d": 30 }[state.updatedWithin];
    all.push({ updatedAfter: new Date(now.getTime() - duration * 86_400_000).toISOString() });
  }
  for (const skill of state.skills.filter((entry) => !entry.exclude)) {
    const filter: PostingFilterInput = { hasSkill: { slug: skill.slug, ...(skill.level ? { level: skill.level } : {}) } };
    all.push(filter);
  }
  for (const skill of state.skills.filter((entry) => entry.exclude)) {
    const filter: PostingFilterInput = { hasSkill: { slug: skill.slug, ...(skill.level ? { level: skill.level } : {}) } };
    all.push({ not: filter });
  }
  return {
    ...(all.length ? { filter: { all } } : {}),
    ...(state.order ? { orderBy: state.order } : {}),
  };
}
