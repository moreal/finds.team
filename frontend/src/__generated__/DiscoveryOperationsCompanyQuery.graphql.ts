/**
 * @generated SignedSource<<638b1a8e482aebbb820dd996ae570bfa>>
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
export type CrawlOutcome = "FAILED" | "SUCCESS" | "%future added value";
export type PostingOrder = "UPDATED_DESC" | "%future added value";
export type SourceProvider = "FLEX" | "GREETING" | "NINEHIRE" | "%future added value";
export type DiscoveryOperationsCompanyQuery$variables = {
  after?: string | null | undefined;
  first?: number | null | undefined;
  orderBy?: PostingOrder | null | undefined;
  slug: string;
};
export type DiscoveryOperationsCompanyQuery$data = {
  readonly careerSite: {
    readonly canonicalBaseUrl: string;
    readonly crawlSummary: {
      readonly finishedAt: string | null | undefined;
      readonly outcome: CrawlOutcome | null | undefined;
    } | null | undefined;
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
    readonly provider: SourceProvider;
    readonly slug: string;
  } | null | undefined;
};
export type DiscoveryOperationsCompanyQuery = {
  response: DiscoveryOperationsCompanyQuery$data;
  variables: DiscoveryOperationsCompanyQuery$variables;
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
  "defaultValue": "UPDATED_DESC",
  "kind": "LocalArgument",
  "name": "orderBy"
},
v3 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "slug"
},
v4 = [
  {
    "kind": "Variable",
    "name": "slug",
    "variableName": "slug"
  }
],
v5 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v6 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "slug",
  "storageKey": null
},
v7 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v8 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "canonicalBaseUrl",
  "storageKey": null
},
v9 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "provider",
  "storageKey": null
},
v10 = {
  "alias": null,
  "args": null,
  "concreteType": "CrawlSummary",
  "kind": "LinkedField",
  "name": "crawlSummary",
  "plural": false,
  "selections": [
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "outcome",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "finishedAt",
      "storageKey": null
    }
  ],
  "storageKey": null
},
v11 = {
  "kind": "Variable",
  "name": "orderBy",
  "variableName": "orderBy"
},
v12 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v13 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v14 = {
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
v15 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v16 = {
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
v17 = [
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
  (v11/*: any*/)
],
v18 = [
  (v5/*: any*/),
  (v6/*: any*/),
  (v7/*: any*/)
],
v19 = [
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
    "name": "DiscoveryOperationsCompanyQuery",
    "selections": [
      {
        "alias": null,
        "args": (v4/*: any*/),
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "careerSite",
        "plural": false,
        "selections": [
          (v5/*: any*/),
          (v6/*: any*/),
          (v7/*: any*/),
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/),
          {
            "alias": "openPostings",
            "args": [
              (v11/*: any*/)
            ],
            "concreteType": "JobPostingConnection",
            "kind": "LinkedField",
            "name": "__DiscoveryOperationsCompany_openPostings_connection",
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
                  (v12/*: any*/),
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
                      (v13/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v14/*: any*/),
              (v15/*: any*/),
              (v16/*: any*/)
            ],
            "storageKey": null
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
      (v3/*: any*/),
      (v2/*: any*/),
      (v1/*: any*/),
      (v0/*: any*/)
    ],
    "kind": "Operation",
    "name": "DiscoveryOperationsCompanyQuery",
    "selections": [
      {
        "alias": null,
        "args": (v4/*: any*/),
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "careerSite",
        "plural": false,
        "selections": [
          (v5/*: any*/),
          (v6/*: any*/),
          (v7/*: any*/),
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/),
          {
            "alias": null,
            "args": (v17/*: any*/),
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
                  (v12/*: any*/),
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "JobPosting",
                    "kind": "LinkedField",
                    "name": "node",
                    "plural": false,
                    "selections": [
                      (v5/*: any*/),
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
                        "selections": (v18/*: any*/),
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
                            "selections": (v19/*: any*/),
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "concreteType": "ClassifiedEmployment",
                            "kind": "LinkedField",
                            "name": "employment",
                            "plural": false,
                            "selections": (v19/*: any*/),
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "concreteType": "ClassifiedRemotePolicy",
                            "kind": "LinkedField",
                            "name": "remote",
                            "plural": false,
                            "selections": (v19/*: any*/),
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
                              (v7/*: any*/),
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
                                "selections": (v18/*: any*/),
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
                      (v13/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v14/*: any*/),
              (v15/*: any*/),
              (v16/*: any*/)
            ],
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v17/*: any*/),
            "filters": [
              "orderBy"
            ],
            "handle": "connection",
            "key": "DiscoveryOperationsCompany_openPostings",
            "kind": "LinkedHandle",
            "name": "openPostings"
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "ed1fb792618fe6660223dcef6902e489",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": "first",
          "cursor": "after",
          "direction": "forward",
          "path": [
            "careerSite",
            "openPostings"
          ]
        }
      ]
    },
    "name": "DiscoveryOperationsCompanyQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsCompanyQuery(\n  $slug: String!\n  $orderBy: PostingOrder = UPDATED_DESC\n  $first: Int = 20\n  $after: String\n) {\n  careerSite(slug: $slug) {\n    id\n    slug\n    displayName\n    canonicalBaseUrl\n    provider\n    crawlSummary {\n      outcome\n      finishedAt\n    }\n    openPostings(first: $first, after: $after, orderBy: $orderBy) {\n      edges {\n        cursor\n        node {\n          ...DiscoveryOperations_job\n          id\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n  }\n}\n\nfragment DiscoveryOperations_job on JobPosting {\n  id\n  title\n  canonicalUrl\n  status\n  updatedAt\n  careerSite {\n    id\n    slug\n    displayName\n  }\n  classification {\n    taxonomyVersion\n    role {\n      value\n      rawValue\n    }\n    employment {\n      value\n      rawValue\n    }\n    remote {\n      value\n      rawValue\n    }\n    location {\n      displayName\n      searchValue\n    }\n    skills {\n      skill {\n        id\n        slug\n        displayName\n      }\n      text\n      level\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "7141969223b89a95bc3f6cfb55c8b078";

export default node;
