/**
 * @generated SignedSource<<dc729156ca43d4d4674bbdfef6005415>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type AccountOperationsUserRefetchQuery$variables = {
  id: string;
};
export type AccountOperationsUserRefetchQuery$data = {
  readonly node: {
    readonly " $fragmentSpreads": FragmentRefs<"AccountOperations_user">;
  } | null | undefined;
};
export type AccountOperationsUserRefetchQuery = {
  response: AccountOperationsUserRefetchQuery$data;
  variables: AccountOperationsUserRefetchQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
  {
    "defaultValue": null,
    "kind": "LocalArgument",
    "name": "id"
  }
],
v1 = [
  {
    "kind": "Variable",
    "name": "id",
    "variableName": "id"
  }
];
return {
  "fragment": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Fragment",
    "metadata": null,
    "name": "AccountOperationsUserRefetchQuery",
    "selections": [
      {
        "alias": null,
        "args": (v1/*: any*/),
        "concreteType": null,
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": [
          {
            "args": null,
            "kind": "FragmentSpread",
            "name": "AccountOperations_user"
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
    "name": "AccountOperationsUserRefetchQuery",
    "selections": [
      {
        "alias": null,
        "args": (v1/*: any*/),
        "concreteType": null,
        "kind": "LinkedField",
        "name": "node",
        "plural": false,
        "selections": [
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "__typename",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "id",
            "storageKey": null
          },
          {
            "kind": "InlineFragment",
            "selections": [
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "roles",
                "storageKey": null
              }
            ],
            "type": "User",
            "abstractKey": null
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "c759e1a3510417dd49bd597f3f9c1b60",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsUserRefetchQuery",
    "operationKind": "query",
    "text": "query AccountOperationsUserRefetchQuery(\n  $id: ID!\n) {\n  node(id: $id) {\n    __typename\n    ...AccountOperations_user\n    id\n  }\n}\n\nfragment AccountOperations_user on User {\n  id\n  roles\n}\n"
  }
};
})();

(node as any).hash = "c3ea77606f8d509c85489ccc659cbde6";

export default node;
