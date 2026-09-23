import { createOperationDescriptor, Environment, getRequest, Network, RecordSource, Store } from "relay-runtime";
import { expect, it } from "vitest";

import { jobsQuery, skillsQuery } from "../DiscoveryOperations";

it("compiled filter connections keep separate records while cursor pages append to their own filter", () => {
  const environment = new Environment({ network: Network.create(() => Promise.reject(new Error("No network expected"))), store: new Store(new RecordSource()) });
  const page = (id: string, status: string, previous = false, next = false) => ({ jobPostings: {
    totalCount: status === "OPEN" ? 2 : 1, error: null,
    edges: [{ cursor: id, node: {
      __typename: "JobPosting", id, title: id, canonicalUrl: `https://jobs.example/${id}`, status,
      updatedAt: "2026-09-22T00:00:00Z", classification: null,
      careerSite: { id: "company", slug: "company", displayName: "Company" },
    } }],
    pageInfo: { startCursor: id, endCursor: id, hasPreviousPage: previous, hasNextPage: next },
  } });
  const open = createOperationDescriptor(getRequest(jobsQuery), { filter: { hasStatus: "OPEN" }, orderBy: "UPDATED_DESC", first: 1 });
  const closed = createOperationDescriptor(getRequest(jobsQuery), { filter: { hasStatus: "CLOSED" }, orderBy: "UPDATED_DESC", first: 1 });
  const next = createOperationDescriptor(getRequest(jobsQuery), { ...open.request.variables, after: "open-1" });
  environment.commitPayload(open, page("open-1", "OPEN", false, true));
  environment.commitPayload(closed, page("closed-1", "CLOSED"));
  environment.commitPayload(next, page("open-2", "OPEN", true));
  const edgeIds = (operation: typeof open) => {
    const data = environment.lookup(operation.fragment).data as { jobPostings: { edges: { node: { __id: string } }[] } };
    // Fragment references identify the normalized record consumed by job-card components.
    return data.jobPostings.edges.map(({ node }) => node.__id);
  };
  expect(edgeIds(open)).toEqual(["open-1", "open-2"]);
  expect(edgeIds(closed)).toEqual(["closed-1"]);
});

it("compiled skill search connections retain each query's membership", () => {
  const environment = new Environment({ network: Network.create(() => Promise.reject(new Error("No network expected"))), store: new Store(new RecordSource()) });
  const query = (text: string) => createOperationDescriptor(getRequest(skillsQuery), { query: text, orderBy: "SLUG_ASC", first: 1 });
  const kotlin = query("kotlin");
  const java = query("java");
  for (const [operation, slug] of [[kotlin, "kotlin"], [java, "java"]] as const) environment.commitPayload(operation, { skills: {
    totalCount: 1, error: null, edges: [{ cursor: slug, node: { id: slug, slug, displayName: slug, __typename: "Skill" } }],
    pageInfo: { startCursor: slug, endCursor: slug, hasNextPage: false, hasPreviousPage: false },
  } });
  expect(environment.lookup(kotlin.fragment).data).toMatchObject({ skills: { edges: [{ node: { slug: "kotlin" } }] } });
  expect(environment.lookup(java.fragment).data).toMatchObject({ skills: { edges: [{ node: { slug: "java" } }] } });
});
