import { csrfHeaders } from '../../security/csrf';

export class SecurityRequestError extends Error {
  constructor(readonly status: number, readonly correlationId: string) { super('보안 요청을 완료하지 못했어요.'); }
}
export async function securityPost<T>(path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
  const requestId = crypto.randomUUID();
  const headers = { 'content-type': 'application/json', 'X-Request-ID': requestId, ...await csrfHeaders(), ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}) };
  let response: Response;
  try { response = await fetch(path, { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers, body: JSON.stringify(body ?? {}) }); }
  catch { throw new SecurityRequestError(0, requestId); }
  if (!response.ok) throw new SecurityRequestError(response.status, response.headers.get('x-request-id') ?? requestId);
  return response.json();
}
export function decodeBase64url(value: string): ArrayBuffer {
  const raw = atob(value.replace(/-/g, '+').replace(/_/g, '/'));
  return Uint8Array.from(raw, char => char.charCodeAt(0)).buffer;
}
export function encodeBase64url(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
type Descriptor = Omit<PublicKeyCredentialDescriptor, 'id'> & { id: string };
type Creation = Omit<PublicKeyCredentialCreationOptions, 'challenge' | 'user' | 'excludeCredentials'> & { challenge: string; user: Omit<PublicKeyCredentialUserEntity, 'id'> & { id: string }; excludeCredentials?: Descriptor[] };
type Request = Omit<PublicKeyCredentialRequestOptions, 'challenge' | 'allowCredentials'> & { challenge: string; allowCredentials?: Descriptor[] };
export function creationOptions(value: Creation): PublicKeyCredentialCreationOptions {
  return { ...value, challenge: decodeBase64url(value.challenge), user: { ...value.user, id: decodeBase64url(value.user.id) }, excludeCredentials: value.excludeCredentials?.map(item => ({ ...item, id: decodeBase64url(item.id) })) };
}
export function requestOptions(value: Request): PublicKeyCredentialRequestOptions {
  return { ...value, challenge: decodeBase64url(value.challenge), allowCredentials: value.allowCredentials?.map(item => ({ ...item, id: decodeBase64url(item.id) })) };
}
function serializedCredential(credential: PublicKeyCredential) {
  const response = credential.response;
  return { id: credential.id, rawId: encodeBase64url(credential.rawId), type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment, clientExtensionResults: credential.getClientExtensionResults(),
    response: response instanceof AuthenticatorAttestationResponse
      ? { clientDataJSON: encodeBase64url(response.clientDataJSON), attestationObject: encodeBase64url(response.attestationObject), transports: response.getTransports() }
      : { clientDataJSON: encodeBase64url(response.clientDataJSON), authenticatorData: encodeBase64url((response as AuthenticatorAssertionResponse).authenticatorData), signature: encodeBase64url((response as AuthenticatorAssertionResponse).signature), userHandle: (response as AuthenticatorAssertionResponse).userHandle ? encodeBase64url((response as AuthenticatorAssertionResponse).userHandle!) : null },
  };
}
function ensureSupported() {
  if (!window.PublicKeyCredential || !navigator.credentials) throw new Error('이 브라우저에서 Passkey를 사용할 수 없어요. 지원되는 브라우저에서 다시 시도해 주세요.');
}
export async function registerPasskey(label: string) {
  ensureSupported();
  const options = await securityPost<Creation>('/webauthn/register/options');
  const credential = await navigator.credentials.create({ publicKey: creationOptions(options) }) as PublicKeyCredential | null;
  if (!credential) throw new DOMException('Cancelled', 'NotAllowedError');
  return securityPost<{ success: boolean; recoveryCode?: string }>('/webauthn/register', { publicKey: { credential: serializedCredential(credential), label } }, crypto.randomUUID());
}
/** Kept only in component memory: uncertain writes must retain key and payload. */
export type AdditionalPasskeyCommand = {
  readonly accountId: string;
  readonly beginKey: string;
  readonly label: string;
  stage: 'begin' | 'credential' | 'complete' | 'cancel';
  /** Opaque management metadata, never the WebAuthn credential ID. */
  passkeyId?: string;
  completion?: { key: string; body: { publicKey: { credential: ReturnType<typeof serializedCredential>; label: string } } };
};
export function additionalPasskeyCommand(label: string, accountId: string): AdditionalPasskeyCommand {
  return { beginKey: crypto.randomUUID(), label, accountId, stage: 'begin' };
}
export async function cancelAdditionalPasskey(command: AdditionalPasskeyCommand) {
  command.stage = 'cancel';
  const result = await securityPost<{ success: boolean }>('/webauthn/register/cancel', {}, command.beginKey);
  if (!result.success) throw new Error('등록 취소를 확인하지 못했어요. 다시 시도해 주세요.');
}
export async function beginAdditionalPasskey(command: AdditionalPasskeyCommand): Promise<'added' | 'cancelled'> {
  ensureSupported();
  if (command.stage === 'cancel') { await cancelAdditionalPasskey(command); return 'cancelled'; }
  if (command.stage === 'begin') {
    const result = await securityPost<{ ready: boolean }>('/webauthn/register/begin', { expectedUserId: command.accountId }, command.beginKey);
    if (!result.ready) throw new Error('등록 준비를 확인하지 못했어요. 같은 요청을 다시 시도해 주세요.');
    command.stage = 'credential';
  }
  if (command.stage === 'credential') {
    const options = await securityPost<Creation>('/webauthn/register/options',
      { expectedUserId: command.accountId, beginKey: command.beginKey });
    try {
      const credential = await navigator.credentials.create({ publicKey: creationOptions(options) }) as PublicKeyCredential | null;
      if (!credential) throw new DOMException('Cancelled', 'NotAllowedError');
      command.completion = { key: crypto.randomUUID(), body: { publicKey: { credential: serializedCredential(credential), label: command.label } } };
      command.stage = 'complete';
    } catch (error) {
      if (error instanceof DOMException && ['NotAllowedError', 'AbortError'].includes(error.name)) {
        await cancelAdditionalPasskey(command);
        return 'cancelled';
      }
      throw error;
    }
  }
  const completion = command.completion!;
  const result = await securityPost<{ success: boolean; passkeyId?: string }>('/webauthn/register', completion.body, completion.key);
  if (!result.success) throw new Error('등록을 확인하지 못했어요. 같은 요청을 다시 시도해 주세요.');
  command.passkeyId = typeof result.passkeyId === 'string' ? result.passkeyId : undefined;
  command.completion = undefined;
  return 'added';
}
export async function loginPasskey() {
  ensureSupported();
  const options = await securityPost<Request>('/webauthn/authenticate/options');
  const credential = await navigator.credentials.get({ publicKey: requestOptions(options) }) as PublicKeyCredential | null;
  if (!credential) throw new DOMException('Cancelled', 'NotAllowedError');
  return securityPost<{ authenticated: boolean }>('/login/webauthn', serializedCredential(credential));
}
export function securityError(error: unknown, proof = false): string {
  if (error instanceof DOMException && ['NotAllowedError', 'AbortError'].includes(error.name)) return 'Passkey 요청이 취소되었거나 시간이 초과되었어요. 다시 시도할 수 있어요.';
  if (error instanceof SecurityRequestError) {
    if (error.status === 401) return proof ? '코드가 올바르지 않거나 만료되었어요. 입력한 정보를 확인해 주세요.' : 'Passkey를 확인하지 못했어요. 다시 시도하거나 계정을 복구해 주세요.';
    if (error.status === 429) return '요청이 너무 많아요. 잠시 후 다시 시도해 주세요.';
    if (error.status === 400) return '입력한 정보를 확인해 주세요.';
    return `요청을 완료하지 못했어요. 다시 시도해 주세요. 문의 번호: ${error.correlationId}`;
  }
  return error instanceof Error && !(error instanceof DOMException) ? error.message : 'Passkey를 사용할 수 없어요. 브라우저 설정을 확인해 주세요.';
}
