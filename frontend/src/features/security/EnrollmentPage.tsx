import { createSignal } from 'solid-js';
import { useNavigate } from '@tanstack/solid-router';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { Link } from '../../ui/Link';
import { OtpForm } from './OtpForm';
import { RecoveryCodeDisplay } from './RecoveryCodeDisplay';
import { registerPasskey, securityError } from './webauthn';
import './security.css';

export function EnrollmentPage(props: { mode: 'enrollment' | 'recovery' }) {
  const navigate = useNavigate();
  const [verified, setVerified] = createSignal(false);
  const [completed, setCompleted] = createSignal(false);
  const [code, setCode] = createSignal('');
  const [label, setLabel] = createSignal('내 Passkey');
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal('');
  async function register(event: SubmitEvent) {
    event.preventDefault(); if (pending()) return;
    setPending(true); setError('');
    try {
      const result = await registerPasskey(label().trim());
      if (!result.success) throw new Error('등록을 완료하지 못했어요. 다시 시도해 주세요.');
      setCode(result.recoveryCode ?? ''); setCompleted(true);
    } catch (error) { setError(securityError(error)); } finally { setPending(false); }
  }
  return <main class="security-page"><h1>{props.mode === 'enrollment' ? '계정 만들기' : '계정 복구'}</h1>
    {code() ? <RecoveryCodeDisplay code={code()} onDone={() => { setCode(''); void navigate({ to: '/login' }); }} /> : completed() ? <><p>Passkey 등록이 완료되었어요. 계속하려면 새 Passkey로 로그인해 주세요. 복구 코드는 다시 표시할 수 없어요. 필요한 경우 로그인 후 새로 발급해 주세요.</p><Link href="/login">Passkey로 로그인</Link></> : verified() ? <>
      <h2>Passkey 등록</h2><p>이 인증은 Passkey 등록에만 사용할 수 있어요. 등록 후 새 Passkey로 로그인해 주세요.</p>
      {props.mode === 'recovery' && <p>등록을 완료하면 기존 Passkey와 세션이 모두 취소돼요.</p>}
      <form onSubmit={register} class="security-form"><TextField label="Passkey 이름" value={label()} required maxlength={80} onInput={event => setLabel(event.currentTarget.value)} />
        {error() && <><p role="alert">{error()}</p><p>이미 등록했다면 <Link href="/login">Passkey로 로그인</Link>한 뒤 계정 보안에서 새 복구 코드를 발급할 수 있어요.</p></>}<Button type="submit" loading={pending()}>Passkey 등록</Button></form>
    </> : <><p>{props.mode === 'recovery' ? '이메일 인증 코드와 저장한 복구 코드가 모두 필요해요.' : '이메일을 확인하고 Passkey를 등록하세요. 비밀번호 없이 계정을 안전하게 사용할 수 있어요.'}</p><OtpForm mode={props.mode} onVerified={() => setVerified(true)} /><Link href="/login">로그인으로 돌아가기</Link></>}
  </main>;
}
