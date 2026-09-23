/**
 * @generated SignedSource<<b46d7dd4bc1cca525a11274ebda70781>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type UserRole = "ADMIN" | "USER" | "%future added value";
export type AdminOperationsViewerQuery$variables = Record<PropertyKey, never>;
export type AdminOperationsViewerQuery$data = {
  readonly viewer: {
    readonly user: {
      readonly id: string;
      readonly roles: ReadonlyArray<UserRole>;
    };
  } | null | undefined;
};
export type AdminOperationsViewerQuery = {
  response: AdminOperationsViewerQuery$data;
  variables: AdminOperationsViewerQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
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
            "name": "roles",
            "storageKey": null
          }
        ],
        "storageKey": null
      }
    ],
    "storageKey": null
  }
];
return {
  "fragment": {
    "argumentDefinitions": [],
    "kind": "Fragment",
    "metadata": null,
    "name": "AdminOperationsViewerQuery",
    "selections": (v0/*: any*/),
    "type": "Query",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": [],
    "kind": "Operation",
    "name": "AdminOperationsViewerQuery",
    "selections": (v0/*: any*/)
  },
  "params": {
    "cacheID": "271b66cd05d3bd9865ceaf376c8ecf18",
    "id": null,
    "metadata": {},
    "name": "AdminOperationsViewerQuery",
    "operationKind": "query",
    "text": "query AdminOperationsViewerQuery {\n  viewer {\n    user {\n      id\n      roles\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "8523054359c7400539046f0c0aae516e";

export default node;
