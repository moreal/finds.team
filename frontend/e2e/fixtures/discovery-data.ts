export function posting(id: number) {
  return { __typename: "JobPosting", id: `job-${id}`, title: `Backend engineer ${id}`, canonicalUrl: `https://example.com/jobs/${id}`,
    status: "OPEN", updatedAt: "2026-09-20T00:00:00Z", careerSite: { id: "site-1", slug: "acme", displayName: "Acme" },
    descriptionText: "Build reliable services.\nWork together with our engineering team.", externalKey: String(id),
    employmentHint: null, locationHint: null, remoteHint: null, sourceUpdatedAt: "2026-09-20T00:00:00Z",
    firstSeenAt: "2026-09-01T00:00:00Z", lastSeenAt: "2026-09-20T00:00:00Z", closedAt: null,
    classification: { taxonomyVersion: 1, role: { value: "BACKEND", rawValue: null }, employment: { value: "FULL_TIME", rawValue: null },
      remote: { value: "REMOTE", rawValue: null }, location: { displayName: "Seoul", searchValue: "seoul" },
      skills: [{ skill: { id: "skill-1", slug: "kotlin", displayName: "Kotlin" }, text: "Kotlin", level: "REQUIRED" },
        { skill: { id: "skill-2", slug: "java", displayName: "Java" }, text: "Java", level: "PREFERRED" }] } };
}

function connection(after: string | undefined, node: (id: number) => unknown) {
  const start = after ? Number(after.slice(7)) + 1 : 1;
  const edges = Array.from({ length: Math.min(20, 23 - start) }, (_, index) => ({ cursor: `cursor-${start + index}`, node: node(start + index) }));
  return { edges, totalCount: 22, error: null, pageInfo: { hasNextPage: start === 1, hasPreviousPage: !!after, startCursor: edges[0]?.cursor, endCursor: edges.at(-1)?.cursor } };
}

export function detailData(operation: string, variables: Record<string, any>) {
  if (operation === "DiscoveryOperationsJobQuery") return { jobPosting: variables.id === "missing" ? null : posting(1) };
  if (operation === "DiscoveryOperationsCompanyQuery") return { careerSite: variables.slug === "missing" ? null : {
    __typename: "CareerSite", id: variables.slug === "acme" ? "site-1" : `site-${variables.slug}`, slug: variables.slug, displayName: "Acme", canonicalBaseUrl: "https://example.com", provider: "GREENHOUSE",
    crawlSummary: { outcome: "SUCCESS", finishedAt: "2026-09-20T00:00:00Z" }, openPostings: companyConnection(variables),
  } };
  if (operation === "DiscoveryOperationsSkillQuery") return { skill: variables.slug === "missing" ? null : {
    __typename: "Skill", id: "skill-1", slug: "kotlin", displayName: "Kotlin",
    companies: connection(variables.companiesAfter, id => ({ __typename: "CareerSite", id: `related-company-${id}`, slug: `company-${id}`, displayName: `Company ${id}` })),
    openPostings: connection(variables.after, posting), requirementCounts: { required: 18, preferred: 3, mentioned: 1 },
    relatedSkills: connection(variables.relatedAfter, id => ({ __typename: "Skill", id: `related-skill-${id}`, slug: `related-${id}`, displayName: `Related ${id}` })),
  } };
}

function companyConnection(variables: Record<string, any>) {
  const match = /^connection-(initial|next)-(FORBIDDEN|INVALID_CURSOR|INVALID_INPUT|INTERNAL)$/.exec(variables.slug);
  if (match && (match[1] === "initial" || variables.after)) return {
    edges: [], totalCount: 22, error: { code: match[2], message: "Private diagnostic must never be shown" },
    pageInfo: { hasNextPage: false, hasPreviousPage: !!variables.after, startCursor: null, endCursor: null },
  };
  return connection(variables.after, posting);
}
