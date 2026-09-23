/**
 * @generated SignedSource<<8b6554a58d5f9c4184da8eef0c5d4bd2>>
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
export type AccountOperationsViewerQuery$variables = {
  first?: number | null | undefined;
  passkeysAfter?: string | null | undefined;
  sessionsAfter?: string | null | undefined;
};
export type AccountOperationsViewerQuery$data = {
  readonly viewer: {
    readonly passkeys: {
      readonly edges: ReadonlyArray<{
        readonly cursor: string;
        readonly node: {
          readonly createdAt: string;
          readonly id: string;
          readonly label: string;
          readonly lastUsedAt: string | null | undefined;
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
    readonly sessions: {
      readonly edges: ReadonlyArray<{
        readonly cursor: string;
        readonly node: {
          readonly createdAt: string;
          readonly current: boolean;
          readonly expiresAt: string;
          readonly id: string;
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
    readonly user: {
      readonly " $fragmentSpreads": FragmentRefs<"AccountOperations_user">;
    };
  } | null | undefined;
};
export type AccountOperationsViewerQuery = {
  response: AccountOperationsViewerQuery$data;
  variables: AccountOperationsViewerQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
  {
    "defaultValue": 20,
    "kind": "LocalArgument",
    "name": "first"
  },
  {
    "defaultValue": null,
    "kind": "LocalArgument",
    "name": "passkeysAfter"
  },
  {
    "defaultValue": null,
    "kind": "LocalArgument",
    "name": "sessionsAfter"
  }
],
v1 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v2 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v3 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "createdAt",
  "storageKey": null
},
v4 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v5 = {
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
v6 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v7 = {
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
v8 = [
  {
    "alias": null,
    "args": null,
    "concreteType": "PasskeyEdge",
    "kind": "LinkedField",
    "name": "edges",
    "plural": true,
    "selections": [
      (v1/*: any*/),
      {
        "alias": null,
        "args": null,
        "concreteType": "Passkey",
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": [
          (v2/*: any*/),
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "label",
            "storageKey": null
          },
          (v3/*: any*/),
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "lastUsedAt",
            "storageKey": null
          },
          (v4/*: any*/)
        ],
        "storageKey": null
      }
    ],
    "storageKey": null
  },
  (v5/*: any*/),
  (v6/*: any*/),
  (v7/*: any*/)
],
v9 = [
  {
    "alias": null,
    "args": null,
    "concreteType": "SessionEdge",
    "kind": "LinkedField",
    "name": "edges",
    "plural": true,
    "selections": [
      (v1/*: any*/),
      {
        "alias": null,
        "args": null,
        "concreteType": "Session",
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": [
          (v2/*: any*/),
          (v3/*: any*/),
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "expiresAt",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "current",
            "storageKey": null
          },
          (v4/*: any*/)
        ],
        "storageKey": null
      }
    ],
    "storageKey": null
  },
  (v5/*: any*/),
  (v6/*: any*/),
  (v7/*: any*/)
],
v10 = {
  "kind": "Variable",
  "name": "first",
  "variableName": "first"
},
v11 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "passkeysAfter"
  },
  (v10/*: any*/)
],
v12 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "sessionsAfter"
  },
  (v10/*: any*/)
];
return {
  "fragment": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Fragment",
    "metadata": null,
    "name": "AccountOperationsViewerQuery",
    "selections": [
      {
        "alias": null,
        "args": null,
        "concreteType": "Viewer",
        "kind": "LinkedField",
        "name": "viewer",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "concreteType": "User",
            "kind": "LinkedField",
            "name": "user",
            "plural": false,
            "selections": [
              {
                "args": null,
                "kind": "FragmentSpread",
                "name": "AccountOperations_user"
              }
            ],
            "storageKey": null
          },
          {
            "alias": "passkeys",
            "args": null,
            "concreteType": "PasskeyConnection",
            "kind": "LinkedField",
            "name": "__AccountOperations_passkeys_connection",
            "plural": false,
            "selections": (v8/*: any*/),
            "storageKey": null
          },
          {
            "alias": "sessions",
            "args": null,
            "concreteType": "SessionConnection",
            "kind": "LinkedField",
            "name": "__AccountOperations_sessions_connection",
            "plural": false,
            "selections": (v9/*: any*/),
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
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsViewerQuery",
    "selections": [
      {
        "alias": null,
        "args": null,
        "concreteType": "Viewer",
        "kind": "LinkedField",
        "name": "viewer",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "concreteType": "User",
            "kind": "LinkedField",
            "name": "user",
            "plural": false,
            "selections": [
              (v2/*: any*/),
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "roles",
                "storageKey": null
              }
            ],
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v11/*: any*/),
            "concreteType": "PasskeyConnection",
            "kind": "LinkedField",
            "name": "passkeys",
            "plural": false,
            "selections": (v8/*: any*/),
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v11/*: any*/),
            "filters": null,
            "handle": "connection",
            "key": "AccountOperations_passkeys",
            "kind": "LinkedHandle",
            "name": "passkeys"
          },
          {
            "alias": null,
            "args": (v12/*: any*/),
            "concreteType": "SessionConnection",
            "kind": "LinkedField",
            "name": "sessions",
            "plural": false,
            "selections": (v9/*: any*/),
            "storageKey": null
          },
          {
            "alias": null,
            "args": (v12/*: any*/),
            "filters": null,
            "handle": "connection",
            "key": "AccountOperations_sessions",
            "kind": "LinkedHandle",
            "name": "sessions"
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "d4ab84082f2f9a55926ab1216ea8e505",
    "id": null,
    "metadata": {
      "connection": [
        {
          "count": "first",
          "cursor": "passkeysAfter",
          "direction": "forward",
          "path": [
            "viewer",
            "passkeys"
          ]
        },
        {
          "count": "first",
          "cursor": "sessionsAfter",
          "direction": "forward",
          "path": [
            "viewer",
            "sessions"
          ]
        }
      ]
    },
    "name": "AccountOperationsViewerQuery",
    "operationKind": "query",
    "text": "query AccountOperationsViewerQuery(\n  $first: Int = 20\n  $passkeysAfter: String\n  $sessionsAfter: String\n) {\n  viewer {\n    user {\n      ...AccountOperations_user\n      id\n    }\n    passkeys(first: $first, after: $passkeysAfter) {\n      edges {\n        cursor\n        node {\n          id\n          label\n          createdAt\n          lastUsedAt\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n    sessions(first: $first, after: $sessionsAfter) {\n      edges {\n        cursor\n        node {\n          id\n          createdAt\n          expiresAt\n          current\n          __typename\n        }\n      }\n      pageInfo {\n        hasNextPage\n        hasPreviousPage\n        startCursor\n        endCursor\n      }\n      totalCount\n      error {\n        code\n        message\n      }\n    }\n  }\n}\n\nfragment AccountOperations_user on User {\n  id\n  roles\n}\n"
  }
};
})();

(node as any).hash = "ee7367e86cef793b7140a810a22d26ce";

export default node;
