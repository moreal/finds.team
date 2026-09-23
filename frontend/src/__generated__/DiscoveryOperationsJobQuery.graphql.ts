/**
 * @generated SignedSource<<7d830c2ca740c742a4fc449978b944ba>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type DiscoveryOperationsJobQuery$variables = {
  id: string;
};
export type DiscoveryOperationsJobQuery$data = {
  readonly jobPosting: {
    readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_jobDetail">;
  } | null | undefined;
};
export type DiscoveryOperationsJobQuery = {
  response: DiscoveryOperationsJobQuery$data;
  variables: DiscoveryOperationsJobQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
  {
    "defaultValue": null,
    "kind": "LocalArgument",
    "name": "id"
  }
],
v1 = [
  {
    "kind": "Variable",
    "name": "id",
    "variableName": "id"
  }
],
v2 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v3 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v4 = [
  (v2/*: any*/),
  {
    "alias": null,
    "args": null,
    "kind": "ScalarField",
    "name": "slug",
    "storageKey": null
  },
  (v3/*: any*/)
],
v5 = [
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
  "fragment": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Fragment",
    "metadata": null,
    "name": "DiscoveryOperationsJobQuery",
    "selections": [
      {
        "alias": null,
        "args": (v1/*: any*/),
        "concreteType": "JobPosting",
        "kind": "LinkedField",
        "name": "jobPosting",
        "plural": false,
        "selections": [
          {
            "args": null,
            "kind": "FragmentSpread",
            "name": "DiscoveryOperations_jobDetail"
          }
        ],
        "storageKey": null
      }
    ],
    "type": "Query",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": (v0/*: any*/),
    "kind": "Operation",
    "name": "DiscoveryOperationsJobQuery",
    "selections": [
      {
        "alias": null,
        "args": (v1/*: any*/),
        "concreteType": "JobPosting",
        "kind": "LinkedField",
        "name": "jobPosting",
        "plural": false,
        "selections": [
          (v2/*: any*/),
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
            "selections": (v4/*: any*/),
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
                "selections": (v5/*: any*/),
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "concreteType": "ClassifiedEmployment",
                "kind": "LinkedField",
                "name": "employment",
                "plural": false,
                "selections": (v5/*: any*/),
                "storageKey": null
              },
              {
                "alias": null,
                "args": null,
                "concreteType": "ClassifiedRemotePolicy",
                "kind": "LinkedField",
                "name": "remote",
                "plural": false,
                "selections": (v5/*: any*/),
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
                  (v3/*: any*/),
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
                    "selections": (v4/*: any*/),
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
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "b985987890ae078b50719ebf9f3d6c3b",
    "id": null,
    "metadata": {},
    "name": "DiscoveryOperationsJobQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsJobQuery(\n  $id: ID!\n) {\n  jobPosting(id: $id) {\n    ...DiscoveryOperations_jobDetail\n    id\n  }\n}\n\nfragment DiscoveryOperations_job on JobPosting {\n  id\n  title\n  canonicalUrl\n  status\n  updatedAt\n  careerSite {\n    id\n    slug\n    displayName\n  }\n  classification {\n    taxonomyVersion\n    role {\n      value\n      rawValue\n    }\n    employment {\n      value\n      rawValue\n    }\n    remote {\n      value\n      rawValue\n    }\n    location {\n      displayName\n      searchValue\n    }\n    skills {\n      skill {\n        id\n        slug\n        displayName\n      }\n      text\n      level\n    }\n  }\n}\n\nfragment DiscoveryOperations_jobDetail on JobPosting {\n  ...DiscoveryOperations_job\n  descriptionText\n  externalKey\n  employmentHint\n  locationHint\n  remoteHint\n  sourceUpdatedAt\n  firstSeenAt\n  lastSeenAt\n  closedAt\n}\n"
  }
};
})();

(node as any).hash = "55b4a7ca9c779a2bc0b84d08e261f002";

export default node;
