/**
 * @generated SignedSource<<fe3ad4a59f175d6729ab4a508153ca70>>
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
export type DiscoveryOperationsSkillQuery$variables = {
  after?: string | null | undefined;
  first?: number | null | undefined;
  slug: string;
};
export type DiscoveryOperationsSkillQuery$data = {
  readonly skill: {
    readonly companies: {
      readonly edges: ReadonlyArray<{
        readonly cursor: string;
        readonly node: {
          readonly displayName: string;
          readonly id: string;
          readonly slug: string;
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
    readonly displayName: string;
    readonly id: string;
    readonly openPostings: {
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
    readonly relatedSkills: {
      readonly edges: ReadonlyArray<{
        readonly cursor: string;
        readonly node: {
          readonly displayName: string;
          readonly id: string;
          readonly slug: string;
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
    readonly requirementCounts: {
      readonly mentioned: number;
      readonly preferred: number;
      readonly required: number;
    };
    readonly slug: string;
  } | null | undefined;
};
export type DiscoveryOperationsSkillQuery = {
  response: DiscoveryOperationsSkillQuery$data;
  variables: DiscoveryOperationsSkillQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "after"
},
v1 = {
  "defaultValue": 20,
  "kind": "LocalArgument",
  "name": "first"
},
v2 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "slug"
},
v3 = [
  {
    "kind": "Variable",
    "name": "slug",
    "variableName": "slug"
  }
],
v4 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v5 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "slug",
  "storageKey": null
},
v6 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v7 = {
  "kind": "Literal",
  "name": "orderBy",
  "value": "ID_ASC"
},
v8 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v9 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v10 = [
  (v4/*: any*/),
  (v5/*: any*/),
  (v6/*: any*/),
  (v9/*: any*/)
],
v11 = {
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
v12 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v13 = {
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
v14 = [
  {
    "alias": null,
    "args": null,
    "concreteType": "CareerSiteEdge",
    "kind": "LinkedField",
    "name": "edges",
    "plural": true,
    "selections": [
      (v8/*: any*/),
      {
        "alias": null,
        "args": null,
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": (v10/*: any*/),
        "storageKey": null
      }
    ],
    "storageKey": null
  },
  (v11/*: any*/),
  (v12/*: any*/),
  (v13/*: any*/)
],
v15 = {
  "kind": "Literal",
  "name": "orderBy",
  "value": "UPDATED_DESC"
},
v16 = {
  "alias": null,
  "args": null,
  "concreteType": "SkillRequirementCounts",
  "kind": "LinkedField",
  "name": "requirementCounts",
  "plural": false,
  "selections": [
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "required",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "preferred",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "mentioned",
      "storageKey": null
    }
  ],
  "storageKey": null
},
v17 = {
  "kind": "Literal",
  "name": "orderBy",
  "value": "SLUG_ASC"
},
v18 = [
  {
    "alias": null,
    "args": null,
    "concreteType": "SkillEdge",
    "kind": "LinkedField",
    "name": "edges",
    "plural": true,
    "selections": [
      (v8/*: any*/),
      {
        "alias": null,
        "args": null,
        "concreteType": "Skill",
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": (v10/*: any*/),
        "storageKey": null
      }
    ],
    "storageKey": null
  },
  (v11/*: any*/),
  (v12/*: any*/),
  (v13/*: any*/)
],
v19 = {
  "kind": "Literal",
  "name": "first",
  "value": 20
},
v20 = [
  (v19/*: any*/),
  (v7/*: any*/)
],
v21 = [
  "orderBy"
],
v22 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "after"
  },
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "first"
  },
  (v15/*: any*/)
],
v23 = [
  (v4/*: any*/),
  (v5/*: any*/),
  (v6/*: any*/)
],
v24 = [
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
],
v25 = [
  (v19/*: any*/),
  (v17/*: any*/)
];
return {
  "fragment": {
    "argumentDefinitions": [
      (v0/*: any*/),
      (v1/*: any*/),
      (v2/*: any*/)
    ],
    "kind": "Fragment",
    "metadata": null,
    "name": "DiscoveryOperationsSkillQuery",
    "selections": [
      {
        "alias": null,
        "args": (v3/*: any*/),
        "concreteType": "Skill",
        "kind": "LinkedField",
        "name": "skill",
        "plural": false,
        "selections": [
          (v4/*: any*/),
          (v5/*: any*/),
          (v6/*: any*/),
          {
            "alias": "companies",
            "args": [
              (v7/*: any*/)
            ],
            "concreteType": "CareerSiteConnection",
            "kind": "LinkedField",
            "name": "__DiscoveryOperationsSkill_companies_connection",
            "plural": false,
            "selections": (v14/*: any*/),
            "storageKey": "__DiscoveryOperationsSkill_companies_connection(orderBy:\"ID_ASC\")"
          },
          {
            "alias": "openPostings",
            "args": [
              (v15/*: any*/)
            ],
            "concreteType": "JobPostingConnection",
            "kind": "LinkedField",
            "name": "__DiscoveryOperationsSkill_openPostings_connection",
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
                  (v8/*: any*/),
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
                      (v9/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v11/*: any*/),
              (v12/*: any*/),
              (v13/*: any*/)
            ],
            "storageKey": "__DiscoveryOperationsSkill_openPostings_connection(orderBy:\"UPDATED_DESC\")"
          },
          (v16/*: any*/),
          {
            "alias": "relatedSkills",
            "args": [
              (v17/*: any*/)
            ],
            "concreteType": "SkillConnection",
            "kind": "LinkedField",
            "name": "__DiscoveryOperations_relatedSkills_connection",
            "plural": false,
            "selections": (v18/*: any*/),
            "storageKey": "__DiscoveryOperations_relatedSkills_connection(orderBy:\"SLUG_ASC\")"
          }
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
      (v2/*: any*/),
      (v1/*: any*/),
      (v0/*: any*/)
    ],
    "kind": "Operation",
    "name": "DiscoveryOperationsSkillQuery",
    "selections": [
      {
        "alias": null,
        "args": (v3/*: any*/),
        "concreteType": "Skill",
        "kind": "LinkedField",
        "name": "skill",
        "plural": false,
        "selections": [
          (v4/*: any*/),
          (v5/*: any*/),
          (v6/*: any*/),
          {
            "alias": null,
            "args": (v20/*: any*/),
            "concreteType": "CareerSiteConnection",
            "kind": "LinkedField",
            "name": "companies",
            "plural": false,
            "selections": (v14/*: any*/),
            "storageKey": "companies(first:20,orderBy:\"ID_ASC\")"
          },
          {
            "alias": null,
            "args": (v20/*: any*/),
            "filters": (v21/*: any*/),
            "handle": "connection",
            "key": "DiscoveryOperationsSkill_companies",
            "kind": "LinkedHandle",
            "name": "companies"
          },
          {
            "alias": null,
            "args": (v22/*: any*/),
            "concreteType": "JobPostingConnection",
            "kind": "LinkedField",
            "name": "openPostings",
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
                  (v8/*: any*/),
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "JobPosting",
                    "kind": "LinkedField",
                    "name": "node",
                    "plural": false,
                    "selections": [
                      (v4/*: any*/),
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
                        "selections": (v23/*: any*/),
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
                            "selections": (v24/*: any*/),
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "concreteType": "ClassifiedEmployment",
                            "kind": "LinkedField",
                            "name": "employment",
                            "plural": false,
                            "selections": (v24/*: any*/),
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "concreteType": "ClassifiedRemotePolicy",
                            "kind": "LinkedField",
                            "name": "remote",
                            "plural": false,
                            "selections": (v24/*: any*/),
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
                              (v6/*: any*/),
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
                                "selections": (v23/*: any*/),
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
                      (v9/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v11/*: any*/),
              (v12/*: any*/),
              (v13/*: any*/)
            ],
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v22/*: any*/),
            "filters": (v21/*: any*/),
            "handle": "connection",
            "key": "DiscoveryOperationsSkill_openPostings",
            "kind": "LinkedHandle",
            "name": "openPostings"
          },
          (v16/*: any*/),
          {
            "alias": null,
            "args": (v25/*: any*/),
            "concreteType": "SkillConnection",
            "kind": "LinkedField",
            "name": "relatedSkills",
            "plural": false,
            "selections": (v18/*: any*/),
            "storageKey": "relatedSkills(first:20,orderBy:\"SLUG_ASC\")"
          },
          {
            "alias": null,
            "args": (v25/*: any*/),
            "filters": (v21/*: any*/),
            "handle": "connection",
            "key": "DiscoveryOperations_relatedSkills",
            "kind": "LinkedHandle",
            "name": "relatedSkills"
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "3cbebbec8540a0d3d7f970e6e4c129f0",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": null,
          "cursor": null,
          "direction": "forward",
          "path": [
            "skill",
            "companies"
          ]
        },
        {
          "count": "first",
          "cursor": "after",
          "direction": "forward",
          "path": [
            "skill",
            "openPostings"
          ]
        },
        {
          "count": null,
          "cursor": null,
          "direction": "forward",
          "path": [
            "skill",
            "relatedSkills"
          ]
        }
      ]
    },
    "name": "DiscoveryOperationsSkillQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsSkillQuery(\n  $slug: String!\n  $first: Int = 20\n  $after: String\n) {\n  skill(slug: $slug) {\n    id\n    slug\n    displayName\n    companies(first: 20, orderBy: ID_ASC) {\n      edges {\n        cursor\n        node {\n          id\n          slug\n          displayName\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n    openPostings(first: $first, after: $after, orderBy: UPDATED_DESC) {\n      edges {\n        cursor\n        node {\n          ...DiscoveryOperations_job\n          id\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n    requirementCounts {\n      required\n      preferred\n      mentioned\n    }\n    relatedSkills(first: 20, orderBy: SLUG_ASC) {\n      edges {\n        cursor\n        node {\n          id\n          slug\n          displayName\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n  }\n}\n\nfragment DiscoveryOperations_job on JobPosting {\n  id\n  title\n  canonicalUrl\n  status\n  updatedAt\n  careerSite {\n    id\n    slug\n    displayName\n  }\n  classification {\n    taxonomyVersion\n    role {\n      value\n      rawValue\n    }\n    employment {\n      value\n      rawValue\n    }\n    remote {\n      value\n      rawValue\n    }\n    location {\n      displayName\n      searchValue\n    }\n    skills {\n      skill {\n        id\n        slug\n        displayName\n      }\n      text\n      level\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "a0a86b368abef25b7dbbe455d999e15b";

export default node;
