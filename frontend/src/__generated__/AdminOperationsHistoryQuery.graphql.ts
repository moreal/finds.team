/**
 * @generated SignedSource<<4d54e8eebe2ee8413e6d89a4fc5c7154>>
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
export type SourceProvider = "FLEX" | "GREETING" | "NINEHIRE" | "%future added value";
export type AdminOperationsHistoryQuery$variables = {
  after?: string | null | undefined;
  first?: number | null | undefined;
  slug: string;
};
export type AdminOperationsHistoryQuery$data = {
  readonly careerSite: {
    readonly canonicalBaseUrl: string;
    readonly crawlHistory: {
      readonly edges: ReadonlyArray<{
        readonly cursor: string;
        readonly node: {
          readonly " $fragmentSpreads": FragmentRefs<"AdminOperations_crawl">;
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
    readonly crawlSummary: {
      readonly finishedAt: string | null | undefined;
      readonly outcome: CrawlOutcome | null | undefined;
    } | null | undefined;
    readonly displayName: string;
    readonly enabled: boolean;
    readonly id: string;
    readonly provider: SourceProvider;
    readonly slug: string;
    readonly successfulIntervalSeconds: number;
  } | null | undefined;
};
export type AdminOperationsHistoryQuery = {
  response: AdminOperationsHistoryQuery$data;
  variables: AdminOperationsHistoryQuery$variables;
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
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "canonicalBaseUrl",
  "storageKey": null
},
v8 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "provider",
  "storageKey": null
},
v9 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "enabled",
  "storageKey": null
},
v10 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "successfulIntervalSeconds",
  "storageKey": null
},
v11 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "outcome",
  "storageKey": null
},
v12 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "finishedAt",
  "storageKey": null
},
v13 = {
  "alias": null,
  "args": null,
  "concreteType": "CrawlSummary",
  "kind": "LinkedField",
  "name": "crawlSummary",
  "plural": false,
  "selections": [
    (v11/*: any*/),
    (v12/*: any*/)
  ],
  "storageKey": null
},
v14 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v15 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v16 = {
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
v17 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v18 = [
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
v19 = {
  "alias": null,
  "args": null,
  "concreteType": "DiscoveryError",
  "kind": "LinkedField",
  "name": "error",
  "plural": false,
  "selections": (v18/*: any*/),
  "storageKey": null
},
v20 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "after"
  },
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "first"
  }
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
    "name": "AdminOperationsHistoryQuery",
    "selections": [
      {
        "alias": null,
        "args": (v3/*: any*/),
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "careerSite",
        "plural": false,
        "selections": [
          (v4/*: any*/),
          (v5/*: any*/),
          (v6/*: any*/),
          (v7/*: any*/),
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/),
          (v13/*: any*/),
          {
            "alias": "crawlHistory",
            "args": null,
            "concreteType": "CrawlRunConnection",
            "kind": "LinkedField",
            "name": "__AdminOperations_crawlHistory_connection",
            "plural": false,
            "selections": [
              {
                "alias": null,
                "args": null,
                "concreteType": "CrawlRunEdge",
                "kind": "LinkedField",
                "name": "edges",
                "plural": true,
                "selections": [
                  (v14/*: any*/),
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "CrawlRun",
                    "kind": "LinkedField",
                    "name": "node",
                    "plural": false,
                    "selections": [
                      {
                        "args": null,
                        "kind": "FragmentSpread",
                        "name": "AdminOperations_crawl"
                      },
                      (v15/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v16/*: any*/),
              (v17/*: any*/),
              (v19/*: any*/)
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
      (v2/*: any*/),
      (v1/*: any*/),
      (v0/*: any*/)
    ],
    "kind": "Operation",
    "name": "AdminOperationsHistoryQuery",
    "selections": [
      {
        "alias": null,
        "args": (v3/*: any*/),
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "careerSite",
        "plural": false,
        "selections": [
          (v4/*: any*/),
          (v5/*: any*/),
          (v6/*: any*/),
          (v7/*: any*/),
          (v8/*: any*/),
          (v9/*: any*/),
          (v10/*: any*/),
          (v13/*: any*/),
          {
            "alias": null,
            "args": (v20/*: any*/),
            "concreteType": "CrawlRunConnection",
            "kind": "LinkedField",
            "name": "crawlHistory",
            "plural": false,
            "selections": [
              {
                "alias": null,
                "args": null,
                "concreteType": "CrawlRunEdge",
                "kind": "LinkedField",
                "name": "edges",
                "plural": true,
                "selections": [
                  (v14/*: any*/),
                  {
                    "alias": null,
                    "args": null,
                    "concreteType": "CrawlRun",
                    "kind": "LinkedField",
                    "name": "node",
                    "plural": false,
                    "selections": [
                      (v4/*: any*/),
                      {
                        "alias": null,
                        "args": null,
                        "kind": "ScalarField",
                        "name": "careerSiteId",
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "kind": "ScalarField",
                        "name": "startedAt",
                        "storageKey": null
                      },
                      (v12/*: any*/),
                      (v11/*: any*/),
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "CrawlChangeCounts",
                        "kind": "LinkedField",
                        "name": "counts",
                        "plural": false,
                        "selections": [
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "fetched",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "inserted",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "updated",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "touched",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "missing",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "closed",
                            "storageKey": null
                          },
                          {
                            "alias": null,
                            "args": null,
                            "kind": "ScalarField",
                            "name": "reopened",
                            "storageKey": null
                          }
                        ],
                        "storageKey": null
                      },
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "ApiError",
                        "kind": "LinkedField",
                        "name": "error",
                        "plural": false,
                        "selections": (v18/*: any*/),
                        "storageKey": null
                      },
                      (v15/*: any*/)
                    ],
                    "storageKey": null
                  }
                ],
                "storageKey": null
              },
              (v16/*: any*/),
              (v17/*: any*/),
              (v19/*: any*/)
            ],
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v20/*: any*/),
            "filters": null,
            "handle": "connection",
            "key": "AdminOperations_crawlHistory",
            "kind": "LinkedHandle",
            "name": "crawlHistory"
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "2e88722c912d79f3e63559d3adcbff9c",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": "first",
          "cursor": "after",
          "direction": "forward",
          "path": [
            "careerSite",
            "crawlHistory"
          ]
        }
      ]
    },
    "name": "AdminOperationsHistoryQuery",
    "operationKind": "query",
    "text": "query AdminOperationsHistoryQuery(\n  $slug: String!\n  $first: Int = 20\n  $after: String\n) {\n  careerSite(slug: $slug) {\n    id\n    slug\n    displayName\n    canonicalBaseUrl\n    provider\n    enabled\n    successfulIntervalSeconds\n    crawlSummary {\n      outcome\n      finishedAt\n    }\n    crawlHistory(first: $first, after: $after) {\n      edges {\n        cursor\n        node {\n          ...AdminOperations_crawl\n          id\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n  }\n}\n\nfragment AdminOperations_crawl on CrawlRun {\n  id\n  careerSiteId\n  startedAt\n  finishedAt\n  outcome\n  counts {\n    fetched\n    inserted\n    updated\n    touched\n    missing\n    closed\n    reopened\n  }\n  error {\n    code\n    message\n  }\n}\n"
  }
};
})();

(node as any).hash = "f06e19f790ee3e4ad54d4b11f130a734";

export default node;
