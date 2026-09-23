/**
 * @generated SignedSource<<daf1aa2e03aa7fc9fc6918729d736ce4>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ReaderFragment } from 'relay-runtime';
export type AuditAction = "ACCOUNT_RECOVERY_COMPLETED" | "ADMIN_CONFIGURATION_CHANGED" | "CAREER_SITE_REGISTERED" | "CAREER_SITE_SETTINGS_CHANGED" | "MANUAL_CRAWL_TRIGGERED" | "PASSKEY_REGISTERED" | "PASSKEY_REMOVED" | "PASSKEY_RENAMED" | "RECOVERY_CODE_ROTATED" | "ROLE_GRANTED" | "ROLE_REVOKED" | "SESSION_REVOKED" | "%future added value";
export type AuditActorKind = "SYSTEM" | "USER" | "%future added value";
export type AuditOutcome = "SUCCEEDED" | "%future added value";
export type SourceProvider = "FLEX" | "GREETING" | "NINEHIRE" | "%future added value";
export type UserRole = "ADMIN" | "USER" | "%future added value";
import { FragmentRefs } from "relay-runtime";
export type AdminOperations_audit$data = {
  readonly action: AuditAction;
  readonly actorKind: AuditActorKind;
  readonly actorUserId: string | null | undefined;
  readonly details: {
    readonly enabled: boolean | null | undefined;
    readonly provider: SourceProvider | null | undefined;
    readonly role: UserRole | null | undefined;
  };
  readonly id: string;
  readonly occurredAt: string;
  readonly outcome: AuditOutcome;
  readonly targetId: string;
  readonly targetType: string;
  readonly " $fragmentType": "AdminOperations_audit";
};
export type AdminOperations_audit$key = {
  readonly " $data"?: AdminOperations_audit$data;
  readonly " $fragmentSpreads": FragmentRefs<"AdminOperations_audit">;
};

import AdminOperationsAuditRefetchQuery_graphql from './AdminOperationsAuditRefetchQuery.graphql';

const node: ReaderFragment = {
  "argumentDefinitions": [],
  "kind": "Fragment",
  "metadata": {
    "refetch": {
      "connection": null,
      "fragmentPathInResult": [
        "node"
      ],
      "operation": AdminOperationsAuditRefetchQuery_graphql,
      "identifierInfo": {
        "identifierField": "id",
        "identifierQueryVariableName": "id"
      }
    }
  },
  "name": "AdminOperations_audit",
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
};

(node as any).hash = "8feaeb39b5bddb0f79997a35c2ffdcf1";

export default node;
