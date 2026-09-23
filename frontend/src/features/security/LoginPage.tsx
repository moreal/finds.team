import { createSignal, onSettled } from 'solid-js';
import { useNavigate } from '@tanstack/solid-router';
import { Button } from '../../ui/Button';
import { Link } from '../../ui/Link';
import { loginPasskey, securityError } from './webauthn';
import './security.css';
export function LoginPage() {
  const [ready, setReady] = createSignal(false);
  onSettled(() => { setReady(true); });
  const navigate = useNavigate();
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal('');
  async function login() {
    if (pending()) return; setPending(true); setError('');
    try { const result = await loginPasskey(); if (!result.authenticated) throw new Error('로그인을 완료하지 못했어요.'); await navigate({ to: '/account/security' }); }
    catch (error) { setError(securityError(error)); } finally { setPending(false); }
  }
  return <main class="security-page"><h1>로그인</h1><p>저장한 Passkey로 로그인하세요.</p>
    {error() && <p role="alert">{error()}</p>}<Button disabled={!ready()} loading={pending()} onClick={() => void login()}>Passkey로 로그인</Button>
    <nav class="security-actions" aria-label="계정 도움말"><Link href="/join">계정 만들기</Link><Link href="/recover">Passkey를 사용할 수 없나요? 계정 복구</Link></nav>
  </main>;
}
