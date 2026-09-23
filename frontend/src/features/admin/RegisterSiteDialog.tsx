import { createSignal } from 'solid-js';
import { TextField } from '../../ui/TextField';
import { Button } from '../../ui/Button';
export function RegisterSiteDialog(props: { disabled: boolean; onConfirm: (input: { url: string; displayName: string }) => void }) {
  const [name, setName] = createSignal('');
  const [url, setUrl] = createSignal('');
  return <form class="admin-form" onSubmit={event => { event.preventDefault(); if (!props.disabled) props.onConfirm({ displayName: name().trim(), url: url().trim() }); }}>
    <TextField label="사이트 이름" required maxlength={200} disabled={props.disabled} value={name()} onInput={event => setName(event.currentTarget.value)} />
    <TextField label="채용 페이지 URL" type="url" required disabled={props.disabled} value={url()} onInput={event => setUrl(event.currentTarget.value)} />
    <p>채용 사이트를 등록해 자동 수집 대상으로 추가해요. 최근 Passkey 인증이 필요해요.</p>
    <Button type="submit" disabled={props.disabled}>등록 확인</Button>
  </form>;
}
