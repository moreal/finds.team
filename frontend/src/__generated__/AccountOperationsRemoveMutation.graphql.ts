/**
 * @generated SignedSource<<f1bf074d9491a15878c8a6313e094493>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type ApiErrorCode = "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type SecurityChangeOutcome = "ALREADY_ROTATED" | "CHANGED" | "REJECTED" | "ROTATED" | "SIGNED_OUT" | "UNCHANGED" | "%future added value";
export type RemovePasskeyInput = {
  clientMutationId?: string | null | undefined;
  idempotencyKey: string;
  passkeyId: string;
};
export type AccountOperationsRemoveMutation$variables = {
  input: RemovePasskeyInput;
};
export type AccountOperationsRemoveMutation$data = {
  readonly removePasskey: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: SecurityChangeOutcome;
  };
};
export type AccountOperationsRemoveMutation = {
  response: AccountOperationsRemoveMutation$data;
  variables: AccountOperationsRemoveMutation$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
  {
    "defaultValue": null,
    "kind": "LocalArgument",
    "name": "input"
  }
],
v1 = [
  {
    "alias": null,
    "args": [
      {
        "kind": "Variable",
        "name": "input",
        "variableName": "input"
      }
    ],
    "concreteType": "SecurityChangePayload",
    "kind": "LinkedField",
    "name": "removePasskey",
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
        "concreteType": "ApiError",
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
      {
        "alias": null,
        "args": null,
        "kind": "ScalarField",
        "name": "clientMutationId",
        "storageKey": null
      }
    ],
    "storageKey": null
  }
];
return {
  "fragment": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Fragment",
    "metadata": null,
    "name": "AccountOperationsRemoveMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsRemoveMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "5eac3cf257ea35f8c9ed991cacc90460",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsRemoveMutation",
    "operationKind": "mutation",
    "text": "mutation AccountOperationsRemoveMutation(\n  $input: RemovePasskeyInput!\n) {\n  removePasskey(input: $input) {\n    outcome\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "b5a3feddfb7135b4c8ffb38268b94a3f";

export default node;
