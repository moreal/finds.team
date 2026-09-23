/**
 * @generated SignedSource<<ca806b22d314d536e7f92c68324c44b6>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type ApiErrorCode = "ACCOUNT_MISMATCH" | "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
export type SourceProvider = "FLEX" | "GREETING" | "NINEHIRE" | "%future added value";
export type RegisterCareerSiteInput = {
  clientMutationId?: string | null | undefined;
  displayName: string;
  idempotencyKey: string;
  url: string;
};
export type AdminOperationsRegisterMutation$variables = {
  input: RegisterCareerSiteInput;
};
export type AdminOperationsRegisterMutation$data = {
  readonly registerCareerSite: {
    readonly clientMutationId: string | null | undefined;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
      readonly providers: ReadonlyArray<SourceProvider>;
    } | null | undefined;
    readonly site: {
      readonly displayName: string;
      readonly id: string;
      readonly slug: string;
    } | null | undefined;
  };
};
export type AdminOperationsRegisterMutation = {
  response: AdminOperationsRegisterMutation$data;
  variables: AdminOperationsRegisterMutation$variables;
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
    "concreteType": "RegisterCareerSitePayload",
    "kind": "LinkedField",
    "name": "registerCareerSite",
    "plural": false,
    "selections": [
      {
        "alias": null,
        "args": null,
        "concreteType": "CareerSite",
        "kind": "LinkedField",
        "name": "site",
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
            "name": "slug",
            "storageKey": null
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "displayName",
            "storageKey": null
          }
        ],
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
          },
          {
            "alias": null,
            "args": null,
            "kind": "ScalarField",
            "name": "providers",
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
    "name": "AdminOperationsRegisterMutation",
    "selections": (v1/*: any*/),
    "type": "Mutation",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "AdminOperationsRegisterMutation",
    "selections": (v1/*: any*/)
  },
  "params": {
    "cacheID": "c14bfe09bee076eb943687aac909bc60",
    "id": null,
    "metadata": {},
    "name": "AdminOperationsRegisterMutation",
    "operationKind": "mutation",
    "text": "mutation AdminOperationsRegisterMutation(\n  $input: RegisterCareerSiteInput!\n) {\n  registerCareerSite(input: $input) {\n    site {\n      id\n      slug\n      displayName\n    }\n    error {\n      code\n      message\n      providers\n    }\n    clientMutationId\n  }\n}\n"
  }
};
})();

(node as any).hash = "59aefd55375931111384ff1c9c1d780a";

export default node;
