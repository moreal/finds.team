import { graphql, createOperationDescriptor, getRequest } from "relay-runtime";

import type { RelayProbeQuery } from "../../__generated__/RelayProbeQuery.graphql";
import { useRelayEnvironment } from "../RelayRoot";

export const probeQuery = graphql`
  query RelayProbeQuery {
    jobPostings(first: 1) {
      edges {
        node {
          id
          marker: title
        }
      }
    }
  }
`;

export function probeResponse(marker: string) {
  return {
    data: {
      jobPostings: { edges: [{ node: { id: "same-record", marker } }] },
    },
  };
}

export function RelayProbe() {
  const environment = useRelayEnvironment();
  const operation = createOperationDescriptor(getRequest(probeQuery), {});
  const data = environment().lookup(operation.fragment).data as RelayProbeQuery["response"];
  return <p>{data.jobPostings.edges[0]?.node.marker}</p>;
}
