import { createFileRoute } from '@tanstack/solid-router';
import { loadAdmin } from '../../features/admin/AdminData';
import { AdminDashboard } from '../../features/admin/AdminDashboard';
import { AdminPending } from '../../features/admin/AdminPending';
export const Route = createFileRoute('/admin/')({
  loader: ({ context }) => loadAdmin(context.relayEnvironment, 'dashboard'),
  pendingComponent: AdminPending,
  headers: () => ({ 'Cache-Control': 'private, no-store' }),
  head: () => ({ meta: [{ title: '수집 현황 | finds.team' }, { name: 'robots', content: 'noindex' }] }),
  component: () => <AdminDashboard load={Route.useLoaderData()()} />,
});
