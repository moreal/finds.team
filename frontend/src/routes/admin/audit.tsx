import { createFileRoute } from '@tanstack/solid-router';
import { loadAdmin } from '../../features/admin/AdminData';
import { AdminDashboard } from '../../features/admin/AdminDashboard';
import { AdminPending } from '../../features/admin/AdminPending';
import { auditSearch } from '../../features/admin/AuditFilters';
export const Route = createFileRoute('/admin/audit')({
  validateSearch: auditSearch,
  loaderDeps: ({ search }) => ({ filter: search }),
  loader: ({ context, deps }) => loadAdmin(context.relayEnvironment, 'audit', deps),
  pendingComponent: AdminPending,
  headers: () => ({ 'Cache-Control': 'private, no-store' }),
  head: () => ({ meta: [{ title: '감사 기록 | finds.team' }, { name: 'robots', content: 'noindex' }] }),
  component: () => <AdminDashboard load={Route.useLoaderData()()} />,
});
