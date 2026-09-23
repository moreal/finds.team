/**
 * @generated SignedSource<<866c9b06d2e7eda5b3d208d4fba20333>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ReaderFragment } from 'relay-runtime';
export type UserRole = "ADMIN" | "USER" | "%future added value";
import { FragmentRefs } from "relay-runtime";
export type AccountOperations_user$data = {
  readonly id: string;
  readonly roles: ReadonlyArray<UserRole>;
  readonly " $fragmentType": "AccountOperations_user";
};
export type AccountOperations_user$key = {
  readonly " $data"?: AccountOperations_user$data;
  readonly " $fragmentSpreads": FragmentRefs<"AccountOperations_user">;
};

import AccountOperationsUserRefetchQuery_graphql from './AccountOperationsUserRefetchQuery.graphql';

const node: ReaderFragment = {
  "argumentDefinitions": [],
  "kind": "Fragment",
  "metadata": {
    "refetch": {
      "connection": null,
      "fragmentPathInResult": [
        "node"
      ],
      "operation": AccountOperationsUserRefetchQuery_graphql,
      "identifierInfo": {
        "identifierField": "id",
        "identifierQueryVariableName": "id"
      }
    }
  },
  "name": "AccountOperations_user",
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
  "type": "User",
  "abstractKey": null
};

(node as any).hash = "c3ea77606f8d509c85489ccc659cbde6";

export default node;
