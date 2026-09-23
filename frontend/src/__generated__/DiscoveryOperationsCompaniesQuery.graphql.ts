/**
 * @generated SignedSource<<28791e91913b59e15c6453a2154941d6>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type ApiErrorCode = "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type CareerSiteOrder = "ID_ASC" | "%future added value";
export type CrawlOutcome = "FAILED" | "SUCCESS" | "%future added value";
export type SourceProvider = "FLEX" | "GREETING" | "NINEHIRE" | "%future added value";
export type DiscoveryOperationsCompaniesQuery$variables = {
  after?: string | null | undefined;
  first?: number | null | undefined;
  orderBy?: CareerSiteOrder | null | undefined;
};
export type DiscoveryOperationsCompaniesQuery$data = {
  readonly careerSites: {
    readonly edges: ReadonlyArray<{
      readonly cursor: string;
      readonly node: {
        readonly crawlSummary: {
          readonly finishedAt: string | null | undefined;
          readonly outcome: CrawlOutcome | null | undefined;
        } | null | undefined;
        readonly displayName: string;
        readonly id: string;
        readonly provider: SourceProvider;
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
};
export type DiscoveryOperationsCompaniesQuery = {
  response: DiscoveryOperationsCompaniesQuery$data;
  variables: DiscoveryOperationsCompaniesQuery$variables;
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
  "defaultValue": "ID_ASC",
  "kind": "LocalArgument",
  "name": "orderBy"
},
v3 = {
  "kind": "Variable",
  "name": "orderBy",
  "variableName": "orderBy"
},
v4 = [
  {
    "alias": null,
    "args": null,
    "concreteType": "CareerSiteEdge",
    "kind": "LinkedField",
    "name": "edges",
    "plural": true,
    "selections": [
      {
        "alias": null,
        "args": null,
        "kind": "ScalarField",
        "name": "cursor",
        "storageKey": null
      },
      {
        "alias": null,
        "args": null,
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "id",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "slug",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "displayName",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "provider",
            "storageKey": null
          },
          {
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
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "__typename",
            "storageKey": null
          }
        ],
        "storageKey": null
      }
    ],
    "storageKey": null
  },
  {
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
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "totalCount",
    "storageKey": null
  },
  {
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
  }
],
v5 = [
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
  (v3/*: any*/)
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
    "name": "DiscoveryOperationsCompaniesQuery",
    "selections": [
      {
        "alias": "careerSites",
        "args": [
          (v3/*: any*/)
        ],
        "concreteType": "CareerSiteConnection",
        "kind": "LinkedField",
        "name": "__DiscoveryOperations_careerSites_connection",
        "plural": false,
        "selections": (v4/*: any*/),
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
    "name": "DiscoveryOperationsCompaniesQuery",
    "selections": [
      {
        "alias": null,
        "args": (v5/*: any*/),
        "concreteType": "CareerSiteConnection",
        "kind": "LinkedField",
        "name": "careerSites",
        "plural": false,
        "selections": (v4/*: any*/),
        "storageKey": null
      },
      {
        "alias": null,
        "args": (v5/*: any*/),
        "filters": [
          "orderBy"
        ],
        "handle": "connection",
        "key": "DiscoveryOperations_careerSites",
        "kind": "LinkedHandle",
        "name": "careerSites"
      }
    ]
  },
  "params": {
    "cacheID": "bed14a8b09d710b9e7eeefb2df1dcabc",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": "first",
          "cursor": "after",
          "direction": "forward",
          "path": [
            "careerSites"
          ]
        }
      ]
    },
    "name": "DiscoveryOperationsCompaniesQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsCompaniesQuery(\n  $orderBy: CareerSiteOrder = ID_ASC\n  $first: Int = 20\n  $after: String\n) {\n  careerSites(orderBy: $orderBy, first: $first, after: $after) {\n    edges {\n      cursor\n      node {\n        id\n        slug\n        displayName\n        provider\n        crawlSummary {\n          outcome\n          finishedAt\n        }\n        __typename\n      }\n    }\n    pageInfo {\n      hasNextPage\n      hasPreviousPage\n      startCursor\n      endCursor\n    }\n    totalCount\n    error {\n      code\n      message\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "e0d5daf66ed4f98f44d942028a3ad64a";

export default node;
