/**
 * @generated SignedSource<<cf9ced35dffe6b21e54ca009a89a212b>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type ApiErrorCode = "ACCOUNT_MISMATCH" | "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type CrawlTriggerOutcome = "BUSY" | "DISABLED" | "FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INFRASTRUCTURE_FAILURE" | "INVALID_INPUT" | "NOT_DUE" | "NOT_FOUND" | "SUCCEEDED" | "TRIGGERED" | "%future added value";
export type TriggerCrawlInput = {
  careerSiteId: string;
  clientMutationId?: string | null | undefined;
  idempotencyKey: string;
};
export type AdminOperationsTriggerMutation$variables = {
  input: TriggerCrawlInput;
};
export type AdminOperationsTriggerMutation$data = {
  readonly triggerCrawl: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly outcome: CrawlTriggerOutcome;
    readonly runId: string | null | undefined;
  };
};
export type AdminOperationsTriggerMutation = {
  response: AdminOperationsTriggerMutation$data;
  variables: AdminOperationsTriggerMutation$variables;
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
    "concreteType": "TriggerCrawlPayload",
    "kind": "LinkedField",
    "name": "triggerCrawl",
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
        "name": "runId",
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
    "name": "AdminOperationsTriggerMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AdminOperationsTriggerMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "f40f6d01263c0fb8f2fb6c6a1441cfc7",
    "id": null,
    "metadata": {},
    "name": "AdminOperationsTriggerMutation",
    "operationKind": "mutation",
    "text": "mutation AdminOperationsTriggerMutation(\n  $input: TriggerCrawlInput!\n) {\n  triggerCrawl(input: $input) {\n    outcome\n    runId\n    error {\n      code\n      message\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "fd64298798929c5b79ab45db123fd71c";

export default node;
