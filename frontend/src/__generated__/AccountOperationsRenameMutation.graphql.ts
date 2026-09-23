/**
 * @generated SignedSource<<c40583171304bf99d0f0ed44a85ea20a>>
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
export type RenamePasskeyInput = {
  clientMutationId?: string | null | undefined;
  expectedUserId: string;
  idempotencyKey: string;
  label: string;
  passkeyId: string;
};
export type AccountOperationsRenameMutation$variables = {
  input: RenamePasskeyInput;
};
export type AccountOperationsRenameMutation$data = {
  readonly renamePasskey: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: SecurityChangeOutcome;
  };
};
export type AccountOperationsRenameMutation = {
  response: AccountOperationsRenameMutation$data;
  variables: AccountOperationsRenameMutation$variables;
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
    "name": "renamePasskey",
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
    "name": "AccountOperationsRenameMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AccountOperationsRenameMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "7eb5653e791e87d5850d98d6ff919851",
    "id": null,
    "metadata": {},
    "name": "AccountOperationsRenameMutation",
    "operationKind": "mutation",
    "text": "mutation AccountOperationsRenameMutation(\n  $input: RenamePasskeyInput!\n) {\n  renamePasskey(input: $input) {\n    outcome\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "5c3949356fea15766d9313d590c8b5a4";

export default node;
