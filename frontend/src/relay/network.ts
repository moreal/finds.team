import { Network, type FetchFunction, type RequestParameters, type Variables } from "relay-runtime";

export class GraphQLRequestError extends Error {
  constructor(readonly status: number, readonly correlationId: string) {
    super(`GraphQL request failed (${status})`);
  }
}

async function fetchGraphQL(
  url: string,
  operation: RequestParameters,
  variables: Variables,
  headers: Headers,
  credentials?: RequestCredentials,
) {
  const requestId = headers.get("x-request-id") ?? crypto.randomUUID();
  headers.set("x-request-id", requestId);
  let response: Response;
  try { response = await fetch(url, {
    method: "POST",
    credentials,
    headers,
    body: JSON.stringify({ query: operation.text, variables, operationName: operation.name }),
  }); } catch { throw new GraphQLRequestError(0, requestId); }
  if (!response.ok) throw new GraphQLRequestError(response.status, response.headers.get("x-request-id") ?? requestId);
  return response.json();
}

function graphqlHeaders() {
  return new Headers({
    "content-type": "application/json",
    accept: "application/graphql-response+json, application/json",
  });
}

export function createServerNetwork(request: Request) {
  const fetchQuery: FetchFunction = (operation, variables) => {
    const url = process.env.FINDS_INTERNAL_GRAPHQL_URL;
    if (!url) throw new Error("FINDS_INTERNAL_GRAPHQL_URL is required for server GraphQL requests");
    const headers = graphqlHeaders();
    const allowed = ["cookie", "accept-language", "x-request-id"];
    if (operation.operationKind === "mutation") allowed.push("x-csrf-token", "x-xsrf-token");
    for (const name of allowed) {
      const value = request.headers.get(name);
      if (value !== null) headers.set(name, value);
    }
    return fetchGraphQL(url, operation, variables, headers);
  };
  return Network.create(fetchQuery);
}

export function createBrowserNetwork() {
  return Network.create((operation, variables) =>
    fetchGraphQL("/graphql", operation, variables, graphqlHeaders(), "same-origin"),
  );
}
