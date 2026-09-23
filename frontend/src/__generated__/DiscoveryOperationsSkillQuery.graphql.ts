/**
 * @generated SignedSource<<782a8193e9aa0f78662e6aff9462c548>>
 * @lightSyntaxTransform
 * @nogrep
 * @codegen-command: node scripts/relay.ts
 */

/* tslint:disable */
/* eslint-disable */
// @ts-nocheck

import { ConcreteRequest } from 'relay-runtime';
import { FragmentRefs } from "relay-runtime";
export type CareerSiteOrder = "ID_ASC" | "%future added value";
export type PostingOrder = "UPDATED_DESC" | "%future added value";
export type SkillOrder = "SLUG_ASC" | "%future added value";
export type DiscoveryOperationsSkillQuery$variables = {
  after?: string | null | undefined;
  companiesAfter?: string | null | undefined;
  companiesFirst?: number | null | undefined;
  companiesOrder?: CareerSiteOrder | null | undefined;
  first?: number | null | undefined;
  includeCompanies?: boolean | null | undefined;
  includePostings?: boolean | null | undefined;
  includeRelated?: boolean | null | undefined;
  postingsOrder?: PostingOrder | null | undefined;
  relatedAfter?: string | null | undefined;
  relatedFirst?: number | null | undefined;
  relatedOrder?: SkillOrder | null | undefined;
  slug: string;
};
export type DiscoveryOperationsSkillQuery$data = {
  readonly skill: {
    readonly " $fragmentSpreads": FragmentRefs<"DiscoveryOperations_skill">;
  } | null | undefined;
};
export type DiscoveryOperationsSkillQuery = {
  response: DiscoveryOperationsSkillQuery$data;
  variables: DiscoveryOperationsSkillQuery$variables;
};

const node: ConcreteRequest = (function(){
var v0 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "after"
},
v1 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "companiesAfter"
},
v2 = {
  "defaultValue": 20,
  "kind": "LocalArgument",
  "name": "companiesFirst"
},
v3 = {
  "defaultValue": "ID_ASC",
  "kind": "LocalArgument",
  "name": "companiesOrder"
},
v4 = {
  "defaultValue": 20,
  "kind": "LocalArgument",
  "name": "first"
},
v5 = {
  "defaultValue": true,
  "kind": "LocalArgument",
  "name": "includeCompanies"
},
v6 = {
  "defaultValue": true,
  "kind": "LocalArgument",
  "name": "includePostings"
},
v7 = {
  "defaultValue": true,
  "kind": "LocalArgument",
  "name": "includeRelated"
},
v8 = {
  "defaultValue": "UPDATED_DESC",
  "kind": "LocalArgument",
  "name": "postingsOrder"
},
v9 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "relatedAfter"
},
v10 = {
  "defaultValue": 20,
  "kind": "LocalArgument",
  "name": "relatedFirst"
},
v11 = {
  "defaultValue": "SLUG_ASC",
  "kind": "LocalArgument",
  "name": "relatedOrder"
},
v12 = {
  "defaultValue": null,
  "kind": "LocalArgument",
  "name": "slug"
},
v13 = [
  {
    "kind": "Variable",
    "name": "slug",
    "variableName": "slug"
  }
],
v14 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "id",
  "storageKey": null
},
v15 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "slug",
  "storageKey": null
},
v16 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "displayName",
  "storageKey": null
},
v17 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "companiesAfter"
  },
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "companiesFirst"
  },
  {
    "kind": "Variable",
    "name": "orderBy",
    "variableName": "companiesOrder"
  }
],
v18 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "cursor",
  "storageKey": null
},
v19 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "__typename",
  "storageKey": null
},
v20 = [
  (v14/*: any*/),
  (v15/*: any*/),
  (v16/*: any*/),
  (v19/*: any*/)
],
v21 = {
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
v22 = {
  "alias": null,
  "args": null,
  "kind": "ScalarField",
  "name": "totalCount",
  "storageKey": null
},
v23 = {
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
},
v24 = [
  "orderBy"
],
v25 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "after"
  },
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "first"
  },
  {
    "kind": "Variable",
    "name": "orderBy",
    "variableName": "postingsOrder"
  }
],
v26 = [
  (v14/*: any*/),
  (v15/*: any*/),
  (v16/*: any*/)
],
v27 = [
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
],
v28 = [
  {
    "kind": "Variable",
    "name": "after",
    "variableName": "relatedAfter"
  },
  {
    "kind": "Variable",
    "name": "first",
    "variableName": "relatedFirst"
  },
  {
    "kind": "Variable",
    "name": "orderBy",
    "variableName": "relatedOrder"
  }
];
return {
  "fragment": {
    "argumentDefinitions": [
      (v0/*: any*/),
      (v1/*: any*/),
      (v2/*: any*/),
      (v3/*: any*/),
      (v4/*: any*/),
      (v5/*: any*/),
      (v6/*: any*/),
      (v7/*: any*/),
      (v8/*: any*/),
      (v9/*: any*/),
      (v10/*: any*/),
      (v11/*: any*/),
      (v12/*: any*/)
    ],
    "kind": "Fragment",
    "metadata": null,
    "name": "DiscoveryOperationsSkillQuery",
    "selections": [
      {
        "alias": null,
        "args": (v13/*: any*/),
        "concreteType": "Skill",
        "kind": "LinkedField",
        "name": "skill",
        "plural": false,
        "selections": [
          {
            "args": null,
            "kind": "FragmentSpread",
            "name": "DiscoveryOperations_skill"
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
    "argumentDefinitions": [
      (v12/*: any*/),
      (v4/*: any*/),
      (v0/*: any*/),
      (v8/*: any*/),
      (v2/*: any*/),
      (v1/*: any*/),
      (v3/*: any*/),
      (v10/*: any*/),
      (v9/*: any*/),
      (v11/*: any*/),
      (v5/*: any*/),
      (v6/*: any*/),
      (v7/*: any*/)
    ],
    "kind": "Operation",
    "name": "DiscoveryOperationsSkillQuery",
    "selections": [
      {
        "alias": null,
        "args": (v13/*: any*/),
        "concreteType": "Skill",
        "kind": "LinkedField",
        "name": "skill",
        "plural": false,
        "selections": [
          (v14/*: any*/),
          (v15/*: any*/),
          (v16/*: any*/),
          {
            "condition": "includeCompanies",
            "kind": "Condition",
            "passingValue": true,
            "selections": [
              {
                "alias": null,
                "args": (v17/*: any*/),
                "concreteType": "CareerSiteConnection",
                "kind": "LinkedField",
                "name": "companies",
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
                      (v18/*: any*/),
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "CareerSite",
                        "kind": "LinkedField",
                        "name": "node",
                        "plural": false,
                        "selections": (v20/*: any*/),
                        "storageKey": null
                      }
                    ],
                    "storageKey": null
                  },
                  (v21/*: any*/),
                  (v22/*: any*/),
                  (v23/*: any*/)
                ],
                "storageKey": null
              },
              {
                "alias": null,
                "args": (v17/*: any*/),
                "filters": (v24/*: any*/),
                "handle": "connection",
                "key": "DiscoveryOperationsSkill_companies",
                "kind": "LinkedHandle",
                "name": "companies"
              }
            ]
          },
          {
            "condition": "includePostings",
            "kind": "Condition",
            "passingValue": true,
            "selections": [
              {
                "alias": null,
                "args": (v25/*: any*/),
                "concreteType": "JobPostingConnection",
                "kind": "LinkedField",
                "name": "openPostings",
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
                      (v18/*: any*/),
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "JobPosting",
                        "kind": "LinkedField",
                        "name": "node",
                        "plural": false,
                        "selections": [
                          (v14/*: any*/),
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
                            "selections": (v26/*: any*/),
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
                                "selections": (v27/*: any*/),
                                "storageKey": null
                              },
                              {
                                "alias": null,
                                "args": null,
                                "concreteType": "ClassifiedEmployment",
                                "kind": "LinkedField",
                                "name": "employment",
                                "plural": false,
                                "selections": (v27/*: any*/),
                                "storageKey": null
                              },
                              {
                                "alias": null,
                                "args": null,
                                "concreteType": "ClassifiedRemotePolicy",
                                "kind": "LinkedField",
                                "name": "remote",
                                "plural": false,
                                "selections": (v27/*: any*/),
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
                                  (v16/*: any*/),
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
                                    "selections": (v26/*: any*/),
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
                          (v19/*: any*/)
                        ],
                        "storageKey": null
                      }
                    ],
                    "storageKey": null
                  },
                  (v21/*: any*/),
                  (v22/*: any*/),
                  (v23/*: any*/)
                ],
                "storageKey": null
              },
              {
                "alias": null,
                "args": (v25/*: any*/),
                "filters": (v24/*: any*/),
                "handle": "connection",
                "key": "DiscoveryOperationsSkill_openPostings",
                "kind": "LinkedHandle",
                "name": "openPostings"
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
                "alias": null,
                "args": (v28/*: any*/),
                "concreteType": "SkillConnection",
                "kind": "LinkedField",
                "name": "relatedSkills",
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
                      (v18/*: any*/),
                      {
                        "alias": null,
                        "args": null,
                        "concreteType": "Skill",
                        "kind": "LinkedField",
                        "name": "node",
                        "plural": false,
                        "selections": (v20/*: any*/),
                        "storageKey": null
                      }
                    ],
                    "storageKey": null
                  },
                  (v21/*: any*/),
                  (v22/*: any*/),
                  (v23/*: any*/)
                ],
                "storageKey": null
              },
              {
                "alias": null,
                "args": (v28/*: any*/),
                "filters": (v24/*: any*/),
                "handle": "connection",
                "key": "DiscoveryOperations_relatedSkills",
                "kind": "LinkedHandle",
                "name": "relatedSkills"
              }
            ]
          }
        ],
        "storageKey": null
      }
    ]
  },
  "params": {
    "cacheID": "6b6db49db6b279bedaf23c187fab743f",
    "id": null,
    "metadata": {},
    "name": "DiscoveryOperationsSkillQuery",
    "operationKind": "query",
    "text": "query DiscoveryOperationsSkillQuery(\n  $slug: String!\n  $first: Int = 20\n  $after: String\n  $postingsOrder: PostingOrder = UPDATED_DESC\n  $companiesFirst: Int = 20\n  $companiesAfter: String\n  $companiesOrder: CareerSiteOrder = ID_ASC\n  $relatedFirst: Int = 20\n  $relatedAfter: String\n  $relatedOrder: SkillOrder = SLUG_ASC\n  $includeCompanies: Boolean = true\n  $includePostings: Boolean = true\n  $includeRelated: Boolean = true\n) {\n  skill(slug: $slug) {\n    ...DiscoveryOperations_skill\n    id\n  }\n}\n\nfragment DiscoveryOperations_job on JobPosting {\n  id\n  title\n  canonicalUrl\n  status\n  updatedAt\n  careerSite {\n    id\n    slug\n    displayName\n  }\n  classification {\n    taxonomyVersion\n    role {\n      value\n      rawValue\n    }\n    employment {\n      value\n      rawValue\n    }\n    remote {\n      value\n      rawValue\n    }\n    location {\n      displayName\n      searchValue\n    }\n    skills {\n      skill {\n        id\n        slug\n        displayName\n      }\n      text\n      level\n    }\n  }\n}\n\nfragment DiscoveryOperations_skill on Skill {\n  id\n  slug\n  displayName\n  companies(first: $companiesFirst, after: $companiesAfter, orderBy: $companiesOrder) @include(if: $includeCompanies) {\n    edges {\n      cursor\n      node {\n        id\n        slug\n        displayName\n        __typename\n      }\n    }\n    pageInfo {\n      hasNextPage\n      hasPreviousPage\n      startCursor\n      endCursor\n    }\n    totalCount\n    error {\n      code\n      message\n    }\n  }\n  openPostings(first: $first, after: $after, orderBy: $postingsOrder) @include(if: $includePostings) {\n    edges {\n      cursor\n      node {\n        ...DiscoveryOperations_job\n        id\n        __typename\n      }\n    }\n    pageInfo {\n      hasNextPage\n      hasPreviousPage\n      startCursor\n      endCursor\n    }\n    totalCount\n    error {\n      code\n      message\n    }\n  }\n  requirementCounts {\n    required\n    preferred\n    mentioned\n  }\n  relatedSkills(first: $relatedFirst, after: $relatedAfter, orderBy: $relatedOrder) @include(if: $includeRelated) {\n    edges {\n      cursor\n      node {\n        id\n        slug\n        displayName\n        __typename\n      }\n    }\n    pageInfo {\n      hasNextPage\n      hasPreviousPage\n      startCursor\n      endCursor\n    }\n    totalCount\n    error {\n      code\n      message\n    }\n  }\n}\n"
  }
};
})();

(node as any).hash = "871aaad08ffc2c4c7405d654018f38aa";

export default node;
