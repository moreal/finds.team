import { createSignal, onSettled } from 'solid-js';
import { useBlocker } from '@tanstack/solid-router';
import { Button } from '../../ui/Button';

/** Plaintext lives only in the owning component, never Relay or browser storage. */
export function RecoveryCodeDisplay(props: { code: string; onDone: () => void }) {
  const [saved, setSaved] = createSignal(false);
  let leaving = false;
  const [warning, setWarning] = createSignal('');
  useBlocker({ shouldBlockFn: () => { if (leaving) return false; setWarning('계속하기 전에 복구 코드를 저장하고 확인해 주세요.'); return true; }, enableBeforeUnload: () => !leaving });
  // Capture ordinary document links as well as router transitions.
  onSettled(() => {
    const guard = (event: MouseEvent) => { if (!leaving && (event.target as Element)?.closest('a[href]')) { event.preventDefault(); event.stopPropagation(); setWarning('계속하기 전에 복구 코드를 저장하고 확인해 주세요.'); } };
    document.addEventListener('click', guard, true);
    return () => document.removeEventListener('click', guard, true);
  });
  function download() {
    const url = URL.createObjectURL(new Blob([`finds.team recovery code\n${props.code}\n`], { type: 'text/plain' }));
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'finds-team-recovery-code.txt'; anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  return <section class="recovery-display" aria-labelledby="recovery-heading">
    <h2 id="recovery-heading">복구 코드를 저장해 주세요</h2>
    <p>이 코드는 지금 한 번만 보여 드려요. Passkey를 잃어버리면 이메일 인증 코드와 함께 필요해요. 이전 복구 코드는 사용할 수 없어요.</p>
    <p>창을 강제로 닫으면 코드를 잃을 수 있어요. 나중에 Passkey로 로그인하면 새 코드를 발급할 수 있어요.</p>
    <code class="recovery-secret">{props.code}</code>
    <div class="security-actions"><Button variant="secondary" onClick={download}>파일로 저장</Button><Button variant="secondary" onClick={() => window.print()}>인쇄</Button></div>
    <label class="security-ack"><input type="checkbox" checked={saved()} onChange={event => setSaved(event.currentTarget.checked)} />복구 코드를 안전한 곳에 저장했어요</label>
    {warning() && <p role="alert">{warning()}</p>}
    <Button disabled={!saved()} onClick={() => { leaving = true; props.onDone(); }}>계속</Button>
  </section>;
}
