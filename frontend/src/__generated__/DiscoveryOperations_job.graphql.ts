/**
 * @generated SignedSource<<f53be4a6fa3e5c4c351ea25fadd63efd>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ReaderFragment } from 'relay-runtime';
export type EmploymentType = "CONTRACT" | "FULL_TIME" | "INTERNSHIP" | "PART_TIME" | "UNKNOWN" | "%future added value";
export type PostingStatus = "CLOSED" | "OPEN" | "%future added value";
export type RemotePolicy = "HYBRID" | "ONSITE" | "REMOTE" | "UNKNOWN" | "%future added value";
export type RoleCategory = "BACKEND" | "DATA" | "DESIGN" | "DEVOPS" | "FRONTEND" | "FULLSTACK" | "MOBILE" | "PRODUCT" | "QA" | "UNKNOWN" | "%future added value";
export type SkillRequirementLevel = "MENTIONED" | "PREFERRED" | "REQUIRED" | "%future added value";
import { FragmentRefs } from "relay-runtime";
export type DiscoveryOperations_job$data = {
  readonly canonicalUrl: string;
  readonly careerSite: {
    readonly displayName: string;
    readonly id: string;
    readonly slug: string;
  };
  readonly classification: {
    readonly employment: {
      readonly rawValue: string | null | undefined;
      readonly value: EmploymentType;
    };
    readonly location: {
      readonly displayName: string;
      readonly searchValue: string;
    } | null | undefined;
    readonly remote: {
      readonly rawValue: string | null | undefined;
      readonly value: RemotePolicy;
    };
    readonly role: {
      readonly rawValue: string | null | undefined;
      readonly value: RoleCategory;
    };
    readonly skills: ReadonlyArray<{
      readonly level: SkillRequirementLevel;
      readonly skill: {
        readonly displayName: string;
        readonly id: string;
        readonly slug: string;
      } | null | undefined;
      readonly text: string;
    }>;
    readonly taxonomyVersion: number;
  } | null | undefined;
  readonly id: string;
  readonly status: PostingStatus;
  readonly title: string;
  readonly updatedAt: string;
  readonly " $fragmentType": "DiscoveryOperations_job";
};
export type DiscoveryOperations_job$key = {
  readonly " $data"?: DiscoveryOperations_job$data;
  readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_job">;
};

import DiscoveryOperationsJobRefetchQuery_graphql from './DiscoveryOperationsJobRefetchQuery.graphql';

const node: ReaderFragment = (function(){
var v0 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v1 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v2 = [
  (v0/*: any*/),
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "slug",
    "storageKey": null
  },
  (v1/*: any*/)
],
v3 = [
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "value",
    "storageKey": null
  },
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "rawValue",
    "storageKey": null
  }
];
return {
  "argumentDefinitions": [],
  "kind": "Fragment",
  "metadata": {
    "refetch": {
      "connection": null,
      "fragmentPathInResult": [
        "node"
      ],
      "operation": DiscoveryOperationsJobRefetchQuery_graphql,
      "identifierInfo": {
        "identifierField": "id",
        "identifierQueryVariableName": "id"
      }
    }
  },
  "name": "DiscoveryOperations_job",
  "selections": [
    (v0/*: any*/),
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "title",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "canonicalUrl",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "status",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "updatedAt",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "concreteType": "CareerSite",
      "kind": "LinkedField",
      "name": "careerSite",
      "plural": false,
      "selections": (v2/*: any*/),
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "concreteType": "PostingClassification",
      "kind": "LinkedField",
      "name": "classification",
      "plural": false,
      "selections": [
        {
          "alias": null,
          "args": null,
          "kind": "ScalarField",
          "name": "taxonomyVersion",
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "concreteType": "ClassifiedRole",
          "kind": "LinkedField",
          "name": "role",
          "plural": false,
          "selections": (v3/*: any*/),
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "concreteType": "ClassifiedEmployment",
          "kind": "LinkedField",
          "name": "employment",
          "plural": false,
          "selections": (v3/*: any*/),
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "concreteType": "ClassifiedRemotePolicy",
          "kind": "LinkedField",
          "name": "remote",
          "plural": false,
          "selections": (v3/*: any*/),
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "concreteType": "NormalizedLocation",
          "kind": "LinkedField",
          "name": "location",
          "plural": false,
          "selections": [
            (v1/*: any*/),
            {
              "alias": null,
              "args": null,
              "kind": "ScalarField",
              "name": "searchValue",
              "storageKey": null
            }
          ],
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "concreteType": "PostingSkill",
          "kind": "LinkedField",
          "name": "skills",
          "plural": true,
          "selections": [
            {
              "alias": null,
              "args": null,
              "concreteType": "Skill",
              "kind": "LinkedField",
              "name": "skill",
              "plural": false,
              "selections": (v2/*: any*/),
              "storageKey": null
            },
            {
              "alias": null,
              "args": null,
              "kind": "ScalarField",
              "name": "text",
              "storageKey": null
            },
            {
              "alias": null,
              "args": null,
              "kind": "ScalarField",
              "name": "level",
              "storageKey": null
            }
          ],
          "storageKey": null
        }
      ],
      "storageKey": null
    }
  ],
  "type": "JobPosting",
  "abstractKey": null
};
})();

(node as any).hash = "9ac8942a8f518f21da073e34df51a594";

export default node;
