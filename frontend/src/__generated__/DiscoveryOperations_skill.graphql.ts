/**
 * @generated SignedSource<<357b86f21d8330c933902e66ab87dd7c>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ReaderFragment } from 'relay-runtime';
export type ApiErrorCode = "ACCOUNT_MISMATCH" | "ALREADY_REGISTERED" | "AMBIGUOUS_PROVIDER" | "BUSY" | "CRAWL_FAILED" | "DISABLED" | "DISCOVERY_FAILED" | "FORBIDDEN" | "IDEMPOTENCY_CONFLICT" | "INTERNAL" | "INVALID_CURSOR" | "INVALID_FILTER" | "INVALID_INPUT" | "INVALID_PAGE" | "INVALID_URL" | "LAST_CREDENTIAL" | "NOT_DUE" | "NOT_FOUND" | "UNKNOWN_SKILL" | "UNSUPPORTED_PROVIDER" | "%future added value";
import { FragmentRefs } from "relay-runtime";
export type DiscoveryOperations_skill$data = {
  readonly companies?: {
    readonly edges: ReadonlyArray<{
      readonly cursor: string;
      readonly node: {
        readonly displayName: string;
        readonly id: string;
        readonly slug: string;
      };
    }>;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly pageInfo: {
      readonly endCursor: string | null | undefined;
      readonly hasNextPage: boolean;
      readonly hasPreviousPage: boolean;
      readonly startCursor: string | null | undefined;
    };
    readonly totalCount: number;
  };
  readonly displayName: string;
  readonly id: string;
  readonly openPostings?: {
    readonly edges: ReadonlyArray<{
      readonly cursor: string;
      readonly node: {
        readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_job">;
      };
    }>;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly pageInfo: {
      readonly endCursor: string | null | undefined;
      readonly hasNextPage: boolean;
      readonly hasPreviousPage: boolean;
      readonly startCursor: string | null | undefined;
    };
    readonly totalCount: number;
  };
  readonly relatedSkills?: {
    readonly edges: ReadonlyArray<{
      readonly cursor: string;
      readonly node: {
        readonly displayName: string;
        readonly id: string;
        readonly slug: string;
      };
    }>;
    readonly error: {
      readonly code: ApiErrorCode;
      readonly message: string;
    } | null | undefined;
    readonly pageInfo: {
      readonly endCursor: string | null | undefined;
      readonly hasNextPage: boolean;
      readonly hasPreviousPage: boolean;
      readonly startCursor: string | null | undefined;
    };
    readonly totalCount: number;
  };
  readonly requirementCounts: {
    readonly mentioned: number;
    readonly preferred: number;
    readonly required: number;
  };
  readonly slug: string;
  readonly " $fragmentType": "DiscoveryOperations_skill";
};
export type DiscoveryOperations_skill$key = {
  readonly " $data"?: DiscoveryOperations_skill$data;
  readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_skill">;
};

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
  "name": "slug",
  "storageKey": null
},
v2 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v3 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v4 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v5 = [
  (v0/*: any*/),
  (v1/*: any*/),
  (v2/*: any*/),
  (v4/*: any*/)
],
v6 = {
  "alias": null,
  "args": null,
  "concreteType": "PageInfo",
  "kind": "LinkedField",
  "name": "pageInfo",
  "plural": false,
  "selections": [
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "hasNextPage",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "hasPreviousPage",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "startCursor",
      "storageKey": null
    },
    {
      "alias": null,
      "args": null,
      "kind": "ScalarField",
      "name": "endCursor",
      "storageKey": null
    }
  ],
  "storageKey": null
},
v7 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v8 = {
  "alias": null,
  "args": null,
  "concreteType": "DiscoveryError",
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
};
return {
  "argumentDefinitions": [
    {
      "kind": "RootArgument",
      "name": "after"
    },
    {
      "kind": "RootArgument",
      "name": "companiesAfter"
    },
    {
      "kind": "RootArgument",
      "name": "companiesFirst"
    },
    {
      "kind": "RootArgument",
      "name": "companiesOrder"
    },
    {
      "kind": "RootArgument",
      "name": "first"
    },
    {
      "kind": "RootArgument",
      "name": "includeCompanies"
    },
    {
      "kind": "RootArgument",
      "name": "includePostings"
    },
    {
      "kind": "RootArgument",
      "name": "includeRelated"
    },
    {
      "kind": "RootArgument",
      "name": "postingsOrder"
    },
    {
      "kind": "RootArgument",
      "name": "relatedAfter"
    },
    {
      "kind": "RootArgument",
      "name": "relatedFirst"
    },
    {
      "kind": "RootArgument",
      "name": "relatedOrder"
    }
  ],
  "kind": "Fragment",
  "metadata": {
    "connection": [
      {
        "count": "companiesFirst",
        "cursor": "companiesAfter",
        "direction": "forward",
        "path": [
          "companies"
        ]
      },
      {
        "count": "first",
        "cursor": "after",
        "direction": "forward",
        "path": [
          "openPostings"
        ]
      },
      {
        "count": "relatedFirst",
        "cursor": "relatedAfter",
        "direction": "forward",
        "path": [
          "relatedSkills"
        ]
      }
    ]
  },
  "name": "DiscoveryOperations_skill",
  "selections": [
    (v0/*: any*/),
    (v1/*: any*/),
    (v2/*: any*/),
    {
      "condition": "includeCompanies",
      "kind": "Condition",
      "passingValue": true,
      "selections": [
        {
          "alias": "companies",
          "args": [
            {
              "kind": "Variable",
              "name": "orderBy",
              "variableName": "companiesOrder"
            }
          ],
          "concreteType": "CareerSiteConnection",
          "kind": "LinkedField",
          "name": "__DiscoveryOperationsSkill_companies_connection",
          "plural": false,
          "selections": [
            {
              "alias": null,
              "args": null,
              "concreteType": "CareerSiteEdge",
              "kind": "LinkedField",
              "name": "edges",
              "plural": true,
              "selections": [
                (v3/*: any*/),
                {
                  "alias": null,
                  "args": null,
                  "concreteType": "CareerSite",
                  "kind": "LinkedField",
                  "name": "node",
                  "plural": false,
                  "selections": (v5/*: any*/),
                  "storageKey": null
                }
              ],
              "storageKey": null
            },
            (v6/*: any*/),
            (v7/*: any*/),
            (v8/*: any*/)
          ],
          "storageKey": null
        }
      ]
    },
    {
      "condition": "includePostings",
      "kind": "Condition",
      "passingValue": true,
      "selections": [
        {
          "alias": "openPostings",
          "args": [
            {
              "kind": "Variable",
              "name": "orderBy",
              "variableName": "postingsOrder"
            }
          ],
          "concreteType": "JobPostingConnection",
          "kind": "LinkedField",
          "name": "__DiscoveryOperationsSkill_openPostings_connection",
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
                (v3/*: any*/),
                {
                  "alias": null,
                  "args": null,
                  "concreteType": "JobPosting",
                  "kind": "LinkedField",
                  "name": "node",
                  "plural": false,
                  "selections": [
                    {
                      "args": null,
                      "kind": "FragmentSpread",
                      "name": "DiscoveryOperations_job"
                    },
                    (v4/*: any*/)
                  ],
                  "storageKey": null
                }
              ],
              "storageKey": null
            },
            (v6/*: any*/),
            (v7/*: any*/),
            (v8/*: any*/)
          ],
          "storageKey": null
        }
      ]
    },
    {
      "alias": null,
      "args": null,
      "concreteType": "SkillRequirementCounts",
      "kind": "LinkedField",
      "name": "requirementCounts",
      "plural": false,
      "selections": [
        {
          "alias": null,
          "args": null,
          "kind": "ScalarField",
          "name": "required",
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "kind": "ScalarField",
          "name": "preferred",
          "storageKey": null
        },
        {
          "alias": null,
          "args": null,
          "kind": "ScalarField",
          "name": "mentioned",
          "storageKey": null
        }
      ],
      "storageKey": null
    },
    {
      "condition": "includeRelated",
      "kind": "Condition",
      "passingValue": true,
      "selections": [
        {
          "alias": "relatedSkills",
          "args": [
            {
              "kind": "Variable",
              "name": "orderBy",
              "variableName": "relatedOrder"
            }
          ],
          "concreteType": "SkillConnection",
          "kind": "LinkedField",
          "name": "__DiscoveryOperations_relatedSkills_connection",
          "plural": false,
          "selections": [
            {
              "alias": null,
              "args": null,
              "concreteType": "SkillEdge",
              "kind": "LinkedField",
              "name": "edges",
              "plural": true,
              "selections": [
                (v3/*: any*/),
                {
                  "alias": null,
                  "args": null,
                  "concreteType": "Skill",
                  "kind": "LinkedField",
                  "name": "node",
                  "plural": false,
                  "selections": (v5/*: any*/),
                  "storageKey": null
                }
              ],
              "storageKey": null
            },
            (v6/*: any*/),
            (v7/*: any*/),
            (v8/*: any*/)
          ],
          "storageKey": null
        }
      ]
    }
  ],
  "type": "Skill",
  "abstractKey": null
};
})();

(node as any).hash = "af5655f7a3510b6ffbf0859d0033fb6f";

export default node;
