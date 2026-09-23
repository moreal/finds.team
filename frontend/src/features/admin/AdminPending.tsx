import { Skeleton } from '../../ui/Skeleton';
import './admin.css';
export function AdminPending() {
  return <main class="admin-page" aria-busy="true"><p role="status">접근 권한과 데이터를 확인하고 있어요.</p><Skeleton shape="card" /><Skeleton shape="card" /></main>;
}
