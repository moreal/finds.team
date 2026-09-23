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

export const jobDetail = graphql`
  fragment DiscoveryOperations_jobDetail on JobPosting {
    ...DiscoveryOperations_job
    descriptionText
    externalKey
    employmentHint
    locationHint
    remoteHint
    sourceUpdatedAt
    firstSeenAt
    lastSeenAt
    closedAt
  }
`;

export const jobQuery = graphql`
  query DiscoveryOperationsJobQuery($id: ID!) {
    jobPosting(id: $id) { ...DiscoveryOperations_jobDetail }
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
  query DiscoveryOperationsCompanyQuery(
    $slug: String!, $orderBy: PostingOrder = UPDATED_DESC, $first: Int = 20, $after: String
  ) {
    careerSite(slug: $slug) {
      id slug displayName canonicalBaseUrl provider
      crawlSummary { outcome finishedAt }
      openPostings(first: $first, after: $after, orderBy: $orderBy)
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
  query DiscoveryOperationsSkillQuery(
    $slug: String!, $first: Int = 20, $after: String, $postingsOrder: PostingOrder = UPDATED_DESC,
    $companiesFirst: Int = 20, $companiesAfter: String, $companiesOrder: CareerSiteOrder = ID_ASC,
    $relatedFirst: Int = 20, $relatedAfter: String, $relatedOrder: SkillOrder = SLUG_ASC
  ) {
    skill(slug: $slug) {
      id slug displayName
      companies(first: $companiesFirst, after: $companiesAfter, orderBy: $companiesOrder)
      @connection(key: "DiscoveryOperationsSkill_companies", filters: ["orderBy"]) {
        edges { cursor node { id slug displayName } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
      openPostings(first: $first, after: $after, orderBy: $postingsOrder)
      @connection(key: "DiscoveryOperationsSkill_openPostings", filters: ["orderBy"]) {
        edges { cursor node { ...DiscoveryOperations_job } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
      requirementCounts { required preferred mentioned }
      relatedSkills(first: $relatedFirst, after: $relatedAfter, orderBy: $relatedOrder)
      @connection(key: "DiscoveryOperations_relatedSkills", filters: ["orderBy"]) {
        edges { cursor node { id slug displayName } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
    }
  }
`;

export const companiesQuery = graphql`
  query DiscoveryOperationsCompaniesQuery($orderBy: CareerSiteOrder = ID_ASC, $first: Int = 20, $after: String) {
    careerSites(orderBy: $orderBy, first: $first, after: $after)
    @connection(key: "DiscoveryOperations_careerSites", filters: ["orderBy"]) {
      edges { cursor node { id slug displayName provider crawlSummary { outcome finishedAt } } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount
      error { code message }
    }
  }
`;

export const skillsQuery = graphql`
  query DiscoveryOperationsSkillsQuery($query: String, $orderBy: SkillOrder = SLUG_ASC, $first: Int = 20, $after: String) {
    skills(query: $query, orderBy: $orderBy, first: $first, after: $after)
    @connection(key: "DiscoveryOperations_skills", filters: ["query", "orderBy"]) {
      edges { cursor node { id slug displayName } }
      pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
      totalCount
      error { code message }
    }
  }
`;
