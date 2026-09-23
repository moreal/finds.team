import { createSignal, onSettled } from 'solid-js';
import { Button } from '../../ui/Button';
import { TextField } from '../../ui/TextField';
import { securityError, securityPost } from './webauthn';

export function OtpForm(props: { mode: 'enrollment' | 'recovery'; onVerified: () => void }) {
  const [ready, setReady] = createSignal(false);
  onSettled(() => { setReady(true); });
  const [email, setEmail] = createSignal('');
  const [otp, setOtp] = createSignal('');
  const [recovery, setRecovery] = createSignal('');
  const [sent, setSent] = createSignal(false);
  const [pending, setPending] = createSignal(false);
  const [error, setError] = createSignal('');
  async function submit(event: SubmitEvent) {
    event.preventDefault();
    if (pending()) return;
    setPending(true); setError('');
    try {
      if (!sent()) {
        await securityPost(`/auth/${props.mode}/otp/request`, { email: email().trim() }, crypto.randomUUID());
        setSent(true);
      } else {
        const result = await securityPost<{ scope: string }>(`/auth/${props.mode}/otp/verify`, { email: email().trim(), otp: otp().trim(), ...(props.mode === 'recovery' ? { recoveryCode: recovery().trim() } : {}) });
        if (result.scope !== props.mode.toUpperCase()) throw new Error('인증을 다시 시작해 주세요.');
        setOtp(''); setRecovery(''); props.onVerified();
      }
    } catch (error) { setError(securityError(error, true)); }
    finally { setPending(false); }
  }
  return <form onSubmit={submit} class="security-form">
    <TextField label="이메일" type="email" autocomplete="email" required maxlength={254} value={email()} disabled={!ready() || pending() || sent()} onInput={event => setEmail(event.currentTarget.value)} />
    {sent() && <>
      <p role="status">이메일을 확인해 주세요. 인증 가능한 주소라면 코드를 보내 드려요.</p>
      <TextField label="이메일 인증 코드" autocomplete="one-time-code" inputmode="numeric" pattern="[0-9]{6}" maxlength={6} required value={otp()} onInput={event => setOtp(event.currentTarget.value)} />
      {props.mode === 'recovery' && <TextField label="저장한 복구 코드" autocomplete="off" required value={recovery()} onInput={event => setRecovery(event.currentTarget.value)} />}
    </>}
    {error() && <p role="alert">{error()}</p>}
    <Button type="submit" disabled={!ready()} loading={pending()}>{sent() ? '인증 확인' : '인증 코드 보내기'}</Button>
    {sent() && <Button variant="ghost" disabled={pending()} onClick={() => { setSent(false); setOtp(''); setRecovery(''); setError(''); }}>이메일 변경 또는 코드 다시 받기</Button>}
  </form>;
}
