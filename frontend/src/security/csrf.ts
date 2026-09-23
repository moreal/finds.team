/** Session replacement invalidates CSRF tokens, so fetch immediately before a write. */
export async function csrfHeaders(): Promise<Record<string, string>> {
  const response = await fetch('/auth/csrf', { credentials: 'same-origin', cache: 'no-store', headers: { accept: 'application/json' } });
  if (!response.ok) throw new Error('보안 확인을 다시 시도해 주세요.');
  const data = await response.json();
  if (typeof data.token !== 'string' || typeof data.headerName !== 'string') throw new Error('보안 확인을 다시 시도해 주세요.');
  return { [data.headerName]: data.token };
}
