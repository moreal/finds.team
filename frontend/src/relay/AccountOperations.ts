import { graphql } from "relay-runtime";

export const user = graphql`
  fragment AccountOperations_user on User
  @refetchable(queryName: "AccountOperationsUserRefetchQuery") {
    id
    roles
  }
`;

export const viewer = graphql`
  query AccountOperationsViewerQuery($first: Int = 20, $passkeysAfter: String, $sessionsAfter: String) {
    viewer {
      user { ...AccountOperations_user }
      passkeys(first: $first, after: $passkeysAfter)
      @connection(key: "AccountOperations_passkeys") {
        edges { cursor node { id label createdAt lastUsedAt } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
      sessions(first: $first, after: $sessionsAfter)
      @connection(key: "AccountOperations_sessions") {
        edges { cursor node { id createdAt expiresAt current } }
        pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        totalCount
        error { code message }
      }
    }
  }
`;
export const rename = graphql`
  mutation AccountOperationsRenameMutation($input: RenamePasskeyInput!) {
    renamePasskey(input: $input) { outcome error { code message } clientMutationId }
  }
`;
export const remove = graphql`
  mutation AccountOperationsRemoveMutation($input: RemovePasskeyInput!) {
    removePasskey(input: $input) { outcome error { code message } clientMutationId }
  }
`;
export const rotate = graphql`
  mutation AccountOperationsRotateMutation($input: SecurityCommandInput!) {
    rotateRecoveryCode(input: $input) { outcome recoveryCode error { code message } clientMutationId }
  }
`;
export const revoke = graphql`
  mutation AccountOperationsRevokeMutation($input: RevokeSessionInput!) {
    revokeSession(input: $input) { outcome error { code message } clientMutationId }
  }
`;
export const revokeOthers = graphql`
  mutation AccountOperationsRevokeOthersMutation($input: SecurityCommandInput!) {
    revokeOtherSessions(input: $input) { outcome error { code message } clientMutationId }
  }
`;
