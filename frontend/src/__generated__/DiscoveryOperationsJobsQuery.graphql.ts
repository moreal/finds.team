/**
 * @generated SignedSource<<fcb9621bfe626c6efdebbd85676f78c6>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type ApiErrorCode = "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type EmploymentType = "CONTRACT" | "FULL_TIME" | "INTERNSHIP" | "PART_TIME" | "UNKNOWN" | "%future added value";
export type PostingOrder = "UPDATED_DESC" | "%future added value";
export type PostingStatus = "CLOSED" | "OPEN" | "%future added value";
export type RemotePolicy = "HYBRID" | "ONSITE" | "REMOTE" | "UNKNOWN" | "%future added value";
export type RoleCategory = "BACKEND" | "DATA" | "DESIGN" | "DEVOPS" | "FRONTEND" | "FULLSTACK" | "MOBILE" | "PRODUCT" | "QA" | "UNKNOWN" | "%future added value";
export type SkillRequirementLevel = "MENTIONED" | "PREFERRED" | "REQUIRED" | "%future added value";
export type PostingFilterInput = {
  all?: ReadonlyArray<PostingFilterInput> | null | undefined;
  any?: ReadonlyArray<PostingFilterInput> | null | undefined;
  atLocation?: string | null | undefined;
  atSite?: string | null | undefined;
  hasEmployment?: EmploymentType | null | undefined;
  hasRemotePolicy?: RemotePolicy | null | undefined;
  hasRole?: RoleCategory | null | undefined;
  hasSkill?: SkillFilterInput | null | undefined;
  hasStatus?: PostingStatus | null | undefined;
  not?: PostingFilterInput | null | undefined;
  textContains?: string | null | undefined;
  updatedAfter?: any | null | undefined;
};
export type SkillFilterInput = {
  level?: SkillRequirementLevel | null | undefined;
  slug: string;
};
export type DiscoveryOperationsJobsQuery$variables = {
  after?: string | null | undefined;
  filter?: PostingFilterInput | null | undefined;
  first?: number | null | undefined;
  orderBy?: PostingOrder | null | undefined;
};
export type DiscoveryOperationsJobsQuery$data = {
  readonly jobPostings: {
    readonly edges: ReadonlyArray<{
      readonly cursor: string;
      readonly node: {
        readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_job">;
      };
    }>;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly pageInfo: {
      readonly endCursor: string | null | undefined;
      readonly hasNextPage: boolean;
      readonly hasPreviousPage: boolean;
      readonly startCursor: string | null | undefined;
    };
    readonly totalCount: number;
  };
};
export type DiscoveryOperationsJobsQuery = {
  response: DiscoveryOperationsJobsQuery$data;
  variables: DiscoveryOperationsJobsQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "after"
},
v1 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "filter"
},
v2 = {
  "defaultValue": 20,
  "kind": "LocalArgument",
  "name": "first"
},
v3 = {
  "defaultValue": "UPDATED_DESC",
  "kind": "LocalArgument",
  "name": "orderBy"
},
v4 = {
  "kind": "Variable",
  "name": "filter",
  "variableName": "filter"
},
v5 = {
  "kind": "Variable",
  "name": "orderBy",
  "variableName": "orderBy"
},
v6 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v7 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v8 = {
  "alias": null,
  "args": null,
  "concreteType": "PageInfo",
  "kind": "LinkedField",
  "name": "pageInfo",
  "plural": false,
  "selections": [
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "hasNextPage",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "hasPreviousPage",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "startCursor",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "endCursor",
      "storageKey": null
    }
  ],
  "storageKey": null
},
v9 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v10 = {
  "alias": null,
  "args": null,
  "concreteType": "DiscoveryError",
  "kind": "LinkedField",
  "name": "error",
  "plural": false,
  "selections": [
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "code",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "message",
      "storageKey": null
    }
  ],
  "storageKey": null
},
v11 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "after"
  },
  (v4/*: any*/),
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "first"
  },
  (v5/*: any*/)
],
v12 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v13 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v14 = [
  (v12/*: any*/),
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "slug",
    "storageKey": null
  },
  (v13/*: any*/)
],
v15 = [
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "value",
    "storageKey": null
  },
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "rawValue",
    "storageKey": null
  }
];
return {
  "fragment": {
    "argumentDefinitions": [
      (v0/*: any*/),
      (v1/*: any*/),
      (v2/*: any*/),
      (v3/*: any*/)
    ],
    "kind": "Fragment",
    "metadata": null,
    "name": "DiscoveryOperationsJobsQuery",
    "selections": [
      {
        "alias": "jobPostings",
        "args": [
          (v4/*: any*/),
          (v5/*: any*/)
        ],
        "concreteType": "JobPostingConnection",
        "kind": "LinkedField",
        "name": "__DiscoveryOperations_jobPostings_connection",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "concreteType": "JobPostingEdge",
            "kind": "LinkedField",
            "name": "edges",
            "plural": true,
            "selections": [
              (v6/*: any*/),
              {
                "alias": null,
                "args": null,
                "concreteType": "JobPosting",
                "kind": "LinkedField",
                "name": "node",
                "plural": false,
                "selections": [
                  {
                    "args": null,
                    "kind": "FragmentSpread",
                    "name": "DiscoveryOperations_job"
                  },
                  (v7/*: any*/)
                ],
                "storageKey": null
              }
            ],
            "storageKey": null
          },
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/)
        ],
        "storageKey": null
      }
    ],
    "type": "Query",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": [
      (v1/*: any*/),
      (v3/*: any*/),
      (v2/*: any*/),
      (v0/*: any*/)
    ],
    "kind": "Operation",
    "name": "DiscoveryOperationsJobsQuery",
    "selections": [
      {
        "alias": null,
        "args": (v11/*: any*/),
        "concreteType": "JobPostingConnection",
        "kind": "LinkedField",
        "name": "jobPostings",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "concreteType": "JobPostingEdge",
            "kind": "LinkedField",
            "name": "edges",
            "plural": true,
            "selections": [
              (v6/*: any*/),
              {
                "alias": null,
                "args": null,
                "concreteType": "JobPosting",
                "kind": "LinkedField",
                "name": "node",
                "plural": false,
                "selections": [
                  (v12/*: any*/),
                  {
                    "alias": null,
                    "args": null,
                    "kind": "ScalarField",
                    "name": "title",
                    "storageKey": null
                  },
                  {
                    "alias": null,
                    "args": null,
                    "kind": "ScalarField",
                    "name": "canonicalUrl",
                    "storageKey": null
                  },
                  {
                    "alias": null,
                    "args": null,
                    "kind": "ScalarField",
                    "name": "status",
                    "storageKey": null
                  },
                  {
                    "alias": null,
                    "args": null,
                    "kind": "ScalarField",
                    "name": "updatedAt",
                    "storageKey": null
                  },
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "CareerSite",
                    "kind": "LinkedField",
                    "name": "careerSite",
                    "plural": false,
                    "selections": (v14/*: any*/),
                    "storageKey": null
                  },
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "PostingClassification",
                    "kind": "LinkedField",
                    "name": "classification",
                    "plural": false,
                    "selections": [
                      {
                        "alias": null,
                        "args": null,
                        "kind": "ScalarField",
                        "name": "taxonomyVersion",
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "ClassifiedRole",
                        "kind": "LinkedField",
                        "name": "role",
                        "plural": false,
                        "selections": (v15/*: any*/),
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "ClassifiedEmployment",
                        "kind": "LinkedField",
                        "name": "employment",
                        "plural": false,
                        "selections": (v15/*: any*/),
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "ClassifiedRemotePolicy",
                        "kind": "LinkedField",
                        "name": "remote",
                        "plural": false,
                        "selections": (v15/*: any*/),
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "NormalizedLocation",
                        "kind": "LinkedField",
                        "name": "location",
                        "plural": false,
                        "selections": [
                          (v13/*: any*/),
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "searchValue",
                            "storageKey": null
                          }
                        ],
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "PostingSkill",
                        "kind": "LinkedField",
                        "name": "skills",
                        "plural": true,
                        "selections": [
                          {
                            "alias": null,
                            "args": null,
                            "concreteType": "Skill",
                            "kind": "LinkedField",
                            "name": "skill",
                            "plural": false,
                            "selections": (v14/*: any*/),
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "text",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "level",
                            "storageKey": null
                          }
                        ],
                        "storageKey": null
                      }
                    ],
                    "storageKey": null
                  },
                  (v7/*: any*/)
                ],
                "storageKey": null
              }
            ],
            "storageKey": null
          },
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/)
        ],
        "storageKey": null
      },
      {
        "alias": null,
        "args": (v11/*: any*/),
        "filters": [
          "filter",
          "orderBy"
        ],
        "handle": "connection",
        "key": "DiscoveryOperations_jobPostings",
        "kind": "LinkedHandle",
        "name": "jobPostings"
      }
    ]
  },
  "params": {
    "cacheID": "d78868e745e8e709be9fe9357ae0a6dd",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": "first",
          "cursor": "after",
          "direction": "forward",
          "path": [
            "jobPostings"
          ]
        }
      ]
    },
    "name": "DiscoveryOperationsJobsQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsJobsQuery(\n  $filter: PostingFilterInput\n  $orderBy: PostingOrder = UPDATED_DESC\n  $first: Int = 20\n  $after: String\n) {\n  jobPostings(filter: $filter, orderBy: $orderBy, first: $first, after: $after) {\n    edges {\n      cursor\n      node {\n        ...DiscoveryOperations_job\n        id\n        __typename\n      }\n    }\n    pageInfo {\n      hasNextPage\n      hasPreviousPage\n      startCursor\n      endCursor\n    }\n    totalCount\n    error {\n      code\n      message\n    }\n  }\n}\n\nfragment DiscoveryOperations_job on JobPosting {\n  id\n  title\n  canonicalUrl\n  status\n  updatedAt\n  careerSite {\n    id\n    slug\n    displayName\n  }\n  classification {\n    taxonomyVersion\n    role {\n      value\n      rawValue\n    }\n    employment {\n      value\n      rawValue\n    }\n    remote {\n      value\n      rawValue\n    }\n    location {\n      displayName\n      searchValue\n    }\n    skills {\n      skill {\n        id\n        slug\n        displayName\n      }\n      text\n      level\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "d268bc60f2496753059202daa806f89e";

export default node;
