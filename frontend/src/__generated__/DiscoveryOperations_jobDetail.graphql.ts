/**
 * @generated SignedSource<<ff17380aa7aaa11ff4c68032c6407928>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ReaderFragment } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type DiscoveryOperations_jobDetail$data = {
  readonly closedAt: string | null | undefined;
  readonly descriptionText: string;
  readonly employmentHint: string | null | undefined;
  readonly externalKey: string;
  readonly firstSeenAt: string;
  readonly lastSeenAt: string;
  readonly locationHint: string | null | undefined;
  readonly remoteHint: string | null | undefined;
  readonly sourceUpdatedAt: string | null | undefined;
  readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_job">;
  readonly " $fragmentType": "DiscoveryOperations_jobDetail";
};
export type DiscoveryOperations_jobDetail$key = {
  readonly " $data"?: DiscoveryOperations_jobDetail$data;
  readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_jobDetail">;
};

const node: ReaderFragment = {
  "argumentDefinitions": [],
  "kind": "Fragment",
  "metadata": null,
  "name": "DiscoveryOperations_jobDetail",
  "selections": [
    {
      "args": null,
      "kind": "FragmentSpread",
      "name": "DiscoveryOperations_job"
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "descriptionText",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "externalKey",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "employmentHint",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "locationHint",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "remoteHint",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "sourceUpdatedAt",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "firstSeenAt",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "lastSeenAt",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "closedAt",
      "storageKey": null
    }
  ],
  "type": "JobPosting",
  "abstractKey": null
};

(node as any).hash = "df865add31b18d52084003d3064c0a6e";

export default node;
