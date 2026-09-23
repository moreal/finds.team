/**
 * @generated SignedSource<<7bf272ee86d119d720c463c7e56a9f6f>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type AdminOperationsAuditRefetchQuery$variables = {
  id: string;
};
export type AdminOperationsAuditRefetchQuery$data = {
  readonly node: {
    readonly " $fragmentSpreads": FragmentRefs<"AdminOperations_audit">;
  } | null | undefined;
};
export type AdminOperationsAuditRefetchQuery = {
  response: AdminOperationsAuditRefetchQuery$data;
  variables: AdminOperationsAuditRefetchQuery$variables;
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
    "name": "AdminOperationsAuditRefetchQuery",
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
            "name": "AdminOperations_audit"
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
    "name": "AdminOperationsAuditRefetchQuery",
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
                "name": "occurredAt",
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "actorKind",
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "actorUserId",
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "action",
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "targetType",
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "kind": "ScalarField",
                "name": "targetId",
                "storageKey": null
              },
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
                "concreteType": "AuditDetails",
                "kind": "LinkedField",
                "name": "details",
                "plural": false,
                "selections": [
                  {
                    "alias": null,
                    "args": null,
                    "kind": "ScalarField",
                    "name": "role",
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
                    "kind": "ScalarField",
                    "name": "enabled",
                    "storageKey": null
                  }
                ],
                "storageKey": null
              }
            ],
            "type": "AuditEvent",
            "abstractKey": null
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "58bc290403bf3e68f24249003fe7292d",
    "id": null,
    "metadata": {},
    "name": "AdminOperationsAuditRefetchQuery",
    "operationKind": "query",
    "text": "query AdminOperationsAuditRefetchQuery(\n  $id: ID!\n) {\n  node(id: $id) {\n    __typename\n    ...AdminOperations_audit\n    id\n  }\n}\n\nfragment AdminOperations_audit on AuditEvent {\n  id\n  occurredAt\n  actorKind\n  actorUserId\n  action\n  targetType\n  targetId\n  outcome\n  details {\n    role\n    provider\n    enabled\n  }\n}\n"
  }
};
})();

(node as any).hash = "8feaeb39b5bddb0f79997a35c2ffdcf1";

export default node;
