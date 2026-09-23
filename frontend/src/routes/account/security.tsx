import { createFileRoute } from '@tanstack/solid-router';
import { SecurityPage } from '../../features/security/SecurityPage';
import { loadAccountPage } from '../../features/security/AccountPageQuery';
import { readQuery } from '../../relay/fragments';
import { viewer } from '../../relay/AccountOperations';
import { useRelayEnvironment } from '../../relay/RelayRoot';
import type { AccountOperationsViewerQuery } from '../../__generated__/AccountOperationsViewerQuery.graphql';
export const Route = createFileRoute('/account/security')({
  loader: ({ context }) => loadAccountPage(context.relayEnvironment),
  headers: () => ({ 'Cache-Control': 'private, no-store' }),
  head: () => ({ meta: [{ title: '계정 보안 | finds.team' }, { name: 'robots', content: 'noindex' }] }),
  component: () => {
    const data = Route.useLoaderData();
    const environment = useRelayEnvironment();
    return <SecurityPage initialViewer={data().failure ? undefined : readQuery<AccountOperationsViewerQuery>(environment(), viewer, {}).viewer ?? undefined} initialFailure={data().failure} />;
  },
});
