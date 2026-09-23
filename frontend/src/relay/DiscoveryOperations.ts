import { graphql } from "relay-runtime";

export const jobCard = graphql`
  fragment DiscoveryOperations_job on JobPosting
  @refetchable(queryName: "DiscoveryOperationsJobRefetchQuery") {
    id
    title
    canonicalUrl
    status
    updatedAt
    careerSite { id slug displayName }
    classification {
      taxonomyVersion
      role { value rawValue }
      employment { value rawValue }
      remote { value rawValue }
      location { displayName searchValue }
      skills { skill { id slug displayName } text level }
    }
  }
`;

export const jobsQuery = graphql`
  query DiscoveryOperationsJobsQuery(
    $filter: PostingFilterInput, $orderBy: PostingOrder = UPDATED_DESC,
    $first: Int = 20, $after: String
  ) {
    jobPostings(filter: $filter, orderBy: $orderBy, first: $first, after: $after)
    @connection(key: "DiscoveryOperations_jobPostings", filters: ["filter", "orderBy"]) {
      edges { cursor node { ...DiscoveryOperations_job } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount
      error { code message }
    }
  }
`;

export const companyQuery = graphql`
  query DiscoveryOperationsCompanyQuery($slug: String!, $first: Int = 20, $after: String) {
    careerSite(slug: $slug) {
      id slug displayName canonicalBaseUrl provider
      openPostings(first: $first, after: $after, orderBy: UPDATED_DESC)
      @connection(key: "DiscoveryOperationsCompany_openPostings", filters: ["orderBy"]) {
        edges { cursor node { ...DiscoveryOperations_job } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
    }
  }
`;

export const skillQuery = graphql`
  query DiscoveryOperationsSkillQuery($slug: String!, $first: Int = 20, $after: String) {
    skill(slug: $slug) {
      id slug displayName
      companies(first: 20, orderBy: ID_ASC)
      @connection(key: "DiscoveryOperationsSkill_companies", filters: ["orderBy"]) {
        edges { cursor node { id slug displayName } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
      openPostings(first: $first, after: $after, orderBy: UPDATED_DESC)
      @connection(key: "DiscoveryOperationsSkill_openPostings", filters: ["orderBy"]) {
        edges { cursor node { ...DiscoveryOperations_job } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
      requirementCounts { required preferred mentioned }
      relatedSkills(first: 20, orderBy: SLUG_ASC)
      @connection(key: "DiscoveryOperations_relatedSkills", filters: ["orderBy"]) {
        edges { cursor node { id slug displayName } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
    }
  }
`;
