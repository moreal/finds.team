import type {
  EmploymentType,
  PostingOrder,
  RemotePolicy,
  RoleCategory,
  SkillRequirementLevel,
} from "../../__generated__/DiscoveryOperationsJobsQuery.graphql";

export type UpdateWindow = "24h" | "7d" | "30d";

export type JobSkillFilter = {
  slug: string;
  level?: Exclude<SkillRequirementLevel, "%future added value">;
  exclude: boolean;
};

export type JobSearchState = {
  text?: string;
  skills: JobSkillFilter[];
  role?: Exclude<RoleCategory, "%future added value">;
  employment?: Exclude<EmploymentType, "%future added value">;
  remote?: Exclude<RemotePolicy, "%future added value">;
  siteId?: string;
  updatedWithin?: UpdateWindow;
  order?: Exclude<PostingOrder, "%future added value">;
};

export type ParseResult = {
  state: JobSearchState;
  canonicalSearch: string;
  corrections: string[];
};
