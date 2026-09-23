import RelayRuntime, { type Environment } from "relay-runtime";
import { jobsQuery, jobCard } from "../../relay/DiscoveryOperations";
import type { DiscoveryOperationsJobsQuery, DiscoveryOperationsJobsQuery$variables } from "../../__generated__/DiscoveryOperationsJobsQuery.graphql";
import type { DiscoveryOperations_job$data } from "../../__generated__/DiscoveryOperations_job.graphql";
import { parseJobSearch } from "./filterCodec";
import { toPostingFilterInput } from "./filterVariables";
import { GraphQLRequestError } from "../../relay/network";

const { createOperationDescriptor, fetchQuery, getFragment, getRequest, getSelector } = RelayRuntime;

export type JobsVariables = DiscoveryOperationsJobsQuery$variables;
export function jobsOperation(variables: JobsVariables) {
  return createOperationDescriptor(getRequest(jobsQuery), variables);
}

export type JobsFailure = { kind: "error" | "unauthorized" | "forbidden" | "validation"; correlationId?: string };
export function jobsConnectionFailure(code: string | undefined): JobsFailure | undefined {
  if (!code) return undefined;
  if (code === "INVALID_FILTER" || code === "UNKNOWN_SKILL" || code === "INVALID_INPUT") return { kind: "validation" };
  if (code === "FORBIDDEN") return { kind: "forbidden" };
  return jobsFailure(code);
}
export function jobsFailure(error: unknown): JobsFailure {
  if (error instanceof GraphQLRequestError) {
    return { kind: error.status === 401 ? "unauthorized" : error.status === 403 ? "forbidden" : "error", correlationId: error.correlationId };
  }
  // DiscoveryError has no request-id field. Keep a local diagnostic reference
  // for unexpected GraphQL failures without exposing provider details.
  const correlationId = crypto.randomUUID();
  console.error("Jobs query failed", { correlationId });
  return { kind: "error", correlationId };
}

export async function fetchJobs(environment: Environment, variables: JobsVariables, force = false) {
  await fetchQuery<DiscoveryOperationsJobsQuery>(environment, jobsQuery, variables, { fetchPolicy: force ? "network-only" : "store-or-network" }).toPromise();
}

export async function loadJobsPage(environment: Environment, search: string) {
  const parsed = parseJobSearch(search);
  // Start serializes loader data. Hydration and continuation reuse this exact
  // resolved instant instead of recomputing a relative update window.
  const variables: JobsVariables = { ...toPostingFilterInput(parsed.state, new Date()), first: 20 };
  let failure: JobsFailure | undefined;
  try { await fetchJobs(environment, variables); } catch (error) { failure = jobsFailure(error); }
  const code = readJobs(environment, variables).connection?.error?.code;
  failure = jobsConnectionFailure(code) ?? failure;
  const noindex = failure?.kind === "validation";
  return { ...parsed, variables, failure, noindex };
}

export function readJobs(environment: Environment, variables: JobsVariables) {
  const snapshot = environment.lookup(jobsOperation(variables).fragment);
  const connection = (snapshot.data as DiscoveryOperationsJobsQuery["response"] | undefined)?.jobPostings;
  const items = connection?.edges.flatMap(edge => {
    const selector = getSelector(getFragment(jobCard), edge.node);
    if (!selector || selector.kind !== "SingularReaderSelector") return [];
    const data = environment.lookup(selector).data as DiscoveryOperations_job$data;
    return data ? [data] : [];
  }) ?? [];
  return { snapshot, connection, items };
}
