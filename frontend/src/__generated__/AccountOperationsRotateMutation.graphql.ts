/**
 * @generated SignedSource<<4bbba58d833a1638537aa2fd38d42e5c>>
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
export type AccountOperationsRotateMutation$variables = {
  input: SecurityCommandInput;
};
export type AccountOperationsRotateMutation$data = {
  readonly rotateRecoveryCode: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: SecurityChangeOutcome;
    readonly recoveryCode: string | null | undefined;
  };
};
export type AccountOperationsRotateMutation = {
  response: AccountOperationsRotateMutation$data;
  variables: AccountOperationsRotateMutation$variables;
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
    "concreteType": "RotateRecoveryCodePayload",
    "kind": "LinkedField",
    "name": "rotateRecoveryCode",
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
        "name": "recoveryCode",
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
    "name": "AccountOperationsRotateMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsRotateMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "c9b10ae185bded9d015e010c52283ee4",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsRotateMutation",
    "operationKind": "mutation",
    "text": "mutation AccountOperationsRotateMutation(\n  $input: SecurityCommandInput!\n) {\n  rotateRecoveryCode(input: $input) {\n    outcome\n    recoveryCode\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "03effaa6f8b22b1e4e00d6c66a494db0";

export default node;
