import { graphql } from "relay-runtime";

export const viewer = graphql`
  query AdminOperationsViewerQuery { viewer { user { id roles } } }
`;
export const sites = graphql`
  query AdminOperationsSitesQuery($first: Int = 50, $after: String) {
    careerSites(first: $first, after: $after) @connection(key: "AdminOperations_careerSites") {
      edges { cursor node { id slug displayName canonicalBaseUrl provider crawlSummary { outcome finishedAt } } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount error { code message }
    }
  }
`;

export const crawl = graphql`
  fragment AdminOperations_crawl on CrawlRun
  @refetchable(queryName: "AdminOperationsCrawlRefetchQuery") {
    id careerSiteId startedAt finishedAt outcome
    counts { fetched inserted updated touched missing closed reopened }
    error { code message }
  }
`;
export const audit = graphql`
  fragment AdminOperations_audit on AuditEvent
  @refetchable(queryName: "AdminOperationsAuditRefetchQuery") {
    id occurredAt actorKind actorUserId action targetType targetId outcome
    details { role provider enabled }
  }
`;
export const auditPage = graphql`
  query AdminOperationsAuditQuery($filter: AuditFilterInput, $first: Int = 50, $after: String) {
    auditEvents(filter: $filter, first: $first, after: $after)
    @connection(key: "AdminOperations_auditEvents", filters: ["filter"]) {
      edges { cursor node { ...AdminOperations_audit } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount error { code message }
    }
  }
`;
export const history = graphql`
  query AdminOperationsHistoryQuery($slug: String!, $first: Int = 20, $after: String) {
    careerSite(slug: $slug) {
      id slug displayName canonicalBaseUrl provider
      crawlSummary { outcome finishedAt }
      crawlHistory(first: $first, after: $after)
      @connection(key: "AdminOperations_crawlHistory") {
        edges { cursor node { ...AdminOperations_crawl } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount error { code message }
      }
    }
  }
`;
export const statuses = graphql`
  query AdminOperationsStatusesQuery($first: Int = 50, $after: String) {
    crawlStatuses(first: $first, after: $after)
    @connection(key: "AdminOperations_crawlStatuses") {
      edges { cursor node { careerSiteId runId outcome finishedAt error { code message } } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount error { code message }
    }
  }
`;
export const register = graphql`
  mutation AdminOperationsRegisterMutation($input: RegisterCareerSiteInput!) {
    registerCareerSite(input: $input) { site { id slug displayName } error { code message providers } clientMutationId }
  }
`;
export const trigger = graphql`
  mutation AdminOperationsTriggerMutation($input: TriggerCrawlInput!) {
    triggerCrawl(input: $input) { outcome runId error { code message } clientMutationId }
  }
`;
