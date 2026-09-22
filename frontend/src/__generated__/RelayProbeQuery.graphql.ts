/**
 * @generated SignedSource<<eb3465f0e8af6dfdec086cd99fbb5296>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
export type RelayProbeQuery$variables = Record<PropertyKey, never>;
export type RelayProbeQuery$data = {
  readonly jobPostings: {
    readonly edges: ReadonlyArray<{
      readonly node: {
        readonly id: string;
        readonly marker: string;
      };
    }>;
  };
};
export type RelayProbeQuery = {
  response: RelayProbeQuery$data;
  variables: RelayProbeQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = [
  {
    "alias": null,
    "args": [
      {
        "kind": "Literal",
        "name": "first",
        "value": 1
      }
    ],
    "concreteType": "JobPostingConnection",
    "kind": "LinkedField",
    "name": "jobPostings",
    "plural": false,
    "selections": [
      {
        "alias": null,
        "args": null,
        "concreteType": "JobPostingEdge",
        "kind": "LinkedField",
        "name": "edges",
        "plural": true,
        "selections": [
          {
            "alias": null,
            "args": null,
            "concreteType": "JobPosting",
            "kind": "LinkedField",
            "name": "node",
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
                "alias": "marker",
                "args": null,
                "kind": "ScalarField",
                "name": "title",
                "storageKey": null
              }
            ],
            "storageKey": null
          }
        ],
        "storageKey": null
      }
    ],
    "storageKey": "jobPostings(first:1)"
  }
];
return {
  "fragment": {
    "argumentDefinitions": [],
    "kind": "Fragment",
    "metadata": null,
    "name": "RelayProbeQuery",
    "selections": (v0/*: any*/),
    "type": "Query",
    "abstractKey": null
  },
  "kind": "Request",
  "operation": {
    "argumentDefinitions": [],
    "kind": "Operation",
    "name": "RelayProbeQuery",
    "selections": (v0/*: any*/)
  },
  "params": {
    "cacheID": "5c571ad5f9d8fce1b64abd63b52f24c2",
    "id": null,
    "metadata": {},
    "name": "RelayProbeQuery",
    "operationKind": "query",
    "text": "query RelayProbeQuery {\n  jobPostings(first: 1) {\n    edges {\n      node {\n        id\n        marker: title\n      }\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "ffaf15c6452deaeb71b848d6d0444287";

export default node;
