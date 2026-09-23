import { createFileRoute } from '@tanstack/solid-router';
import { loadAdmin } from '../../../features/admin/AdminData';
import { AdminDashboard } from '../../../features/admin/AdminDashboard';
import { AdminPending } from '../../../features/admin/AdminPending';
export const Route = createFileRoute('/admin/sites/$id')({
  loader: ({ context, params }) => loadAdmin(context.relayEnvironment, 'detail', { slug: params.id }),
  pendingComponent: AdminPending,
  headers: () => ({ 'Cache-Control': 'private, no-store' }),
  head: () => ({ meta: [{ title: '사이트 상세 | finds.team' }, { name: 'robots', content: 'noindex' }] }),
  component: () => <AdminDashboard load={Route.useLoaderData()()} />,
});
