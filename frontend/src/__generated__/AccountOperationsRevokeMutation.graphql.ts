/**
 * @generated SignedSource<<f826b40045acc8872c606fce32db2d7b>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type ApiErrorCode = "ACCOUNT_MISMATCH" | "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type SecurityChangeOutcome = "ALREADY_ROTATED" | "CHANGED" | "REJECTED" | "ROTATED" | "SIGNED_OUT" | "UNCHANGED" | "%future added value";
export type RevokeSessionInput = {
  clientMutationId?: string | null | undefined;
  expectedUserId: string;
  idempotencyKey: string;
  sessionId: string;
};
export type AccountOperationsRevokeMutation$variables = {
  input: RevokeSessionInput;
};
export type AccountOperationsRevokeMutation$data = {
  readonly revokeSession: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: SecurityChangeOutcome;
  };
};
export type AccountOperationsRevokeMutation = {
  response: AccountOperationsRevokeMutation$data;
  variables: AccountOperationsRevokeMutation$variables;
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
    "name": "revokeSession",
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
    "name": "AccountOperationsRevokeMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsRevokeMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "641f9dd72796c90334ca372e7995b06a",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsRevokeMutation",
    "operationKind": "mutation",
    "text": "mutation AccountOperationsRevokeMutation(\n  $input: RevokeSessionInput!\n) {\n  revokeSession(input: $input) {\n    outcome\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "ccfe6a80b7c5b2dab70a36fe1bc56009";

export default node;
