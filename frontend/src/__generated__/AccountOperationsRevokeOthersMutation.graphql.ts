/**
 * @generated SignedSource<<f9e123842849bb11eb3a81d7bb6d9d14>>
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
export type SecurityCommandInput = {
  clientMutationId?: string | null | undefined;
  expectedUserId: string;
  idempotencyKey: string;
};
export type AccountOperationsRevokeOthersMutation$variables = {
  input: SecurityCommandInput;
};
export type AccountOperationsRevokeOthersMutation$data = {
  readonly revokeOtherSessions: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: SecurityChangeOutcome;
  };
};
export type AccountOperationsRevokeOthersMutation = {
  response: AccountOperationsRevokeOthersMutation$data;
  variables: AccountOperationsRevokeOthersMutation$variables;
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
    "name": "revokeOtherSessions",
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
    "name": "AccountOperationsRevokeOthersMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsRevokeOthersMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "d2d89f0f6d9e55bbe234828a2ea8defd",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsRevokeOthersMutation",
    "operationKind": "mutation",
    "text": "mutation AccountOperationsRevokeOthersMutation(\n  $input: SecurityCommandInput!\n) {\n  revokeOtherSessions(input: $input) {\n    outcome\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "8bab3dc4d0f3a315f2a7da43d0617d5d";

export default node;
