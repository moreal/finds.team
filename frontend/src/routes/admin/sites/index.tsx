import { createFileRoute } from '@tanstack/solid-router';
import { loadAdmin } from '../../../features/admin/AdminData';
import { AdminDashboard } from '../../../features/admin/AdminDashboard';
import { AdminPending } from '../../../features/admin/AdminPending';
export const Route = createFileRoute('/admin/sites/')({
  validateSearch: (search: Record<string, unknown>) => ({ q: typeof search.q === 'string' ? search.q.trim() : '' }),
  loaderDeps: ({ search }) => search,
  loader: ({ context, deps }) => loadAdmin(context.relayEnvironment, 'sites', deps),
  pendingComponent: AdminPending,
  headers: () => ({ 'Cache-Control': 'private, no-store' }),
  head: () => ({ meta: [{ title: '사이트 관리 | finds.team' }, { name: 'robots', content: 'noindex' }] }),
  component: () => <AdminDashboard load={Route.useLoaderData()()} />,
});
