# Mail Transport and Delivery Design

## Goal

Provide an Upyo-inspired, provider-neutral Kotlin mail API with composable transports, deterministic testing, priority failover, and outbox delivery. The mail API is independently packaged inside the monorepo and has no dependency on finds.team domain or application modules.

## Modules

```text
mail/core                 Message and transport contracts
mail/transport-pool       Priority selection and fallback
mail/transport-retry      Backoff and retry decorators
mail/transport-smtp       SMTP provider
mail/transport-ses        Amazon SES API provider
mail/transport-testing    Recording and scripted transports
mail/observability        Metrics and tracing decorator

backend/application       VerificationCodeNotifier port
backend/adapter-notification
                          OTP/recovery meaning to MailMessage mapping
backend/adapter-persistence
                          Transactional mail outbox
backend/bootstrap         Provider and decorator composition
```

SMTP and Amazon SES are the initial production-capable transports and prove priority fallback across different delivery protocols. Development uses the recording transport or local SMTP. Deployment configuration orders and enables transports without changing `mail/core` or application ports.

## Core contract

```kotlin
interface MailTransport {
  val provider: MailProvider
  suspend fun send(message: MailMessage): MailDeliveryResult
}

data class MailMessage(
  val id: MailMessageId,
  val from: Mailbox,
  val recipients: Recipients,
  val subject: String,
  val content: MailContent,
  val tags: Set<String>,
)

sealed interface MailDeliveryResult {
  data class Accepted(
    val provider: MailProvider,
    val providerMessageId: String,
  ) : MailDeliveryResult

  data class Rejected(
    val provider: MailProvider,
    val failure: MailFailure,
    val retryable: Boolean,
  ) : MailDeliveryResult

  data class Indeterminate(
    val provider: MailProvider,
    val failure: MailFailure,
  ) : MailDeliveryResult
}
```

`MailMessageId` is globally unique and stable across every retry and provider attempt. A provider adapter sends it as the provider idempotency key when supported.

## Message scope

The initial core supports transactional text and HTML alternatives, named sender and recipient mailboxes, reply-to, provider-neutral tags, and the headers required for correct transactional delivery. Bulk marketing, inbound mail, calendar invitations, arbitrary attachments, and a general MIME construction framework are outside this program.

## Delivery semantics

- `Accepted` means the provider acknowledged ownership and supplied a receipt identifier.
- `Rejected(retryable=true)` means the provider did not accept the message and retry/fallback is allowed.
- `Rejected(retryable=false)` means a permanent request or recipient failure; fallback is forbidden.
- `Indeterminate` means acceptance may already have occurred, commonly after a timeout. Blind cross-provider fallback is forbidden unless the attempted provider guarantees idempotency for the supplied message id.

The priority pool tries the preferred provider first. Retry applies bounded exponential backoff with jitter before eligible fallback. Circuit breaking can temporarily remove a provider after repeated infrastructure failures, but never converts an indeterminate result into a definite rejection.

## Application boundary

Authentication depends only on a semantic port:

```kotlin
interface VerificationCodeNotifier {
  suspend fun deliver(
    recipient: EmailAddress,
    purpose: VerificationPurpose,
    code: VerificationCode,
    expiresAt: Instant,
    idempotencyKey: DeliveryRequestId,
  ): DeliveryRequestId
}
```

`adapter-notification` chooses the localized template and constructs `MailMessage`. Neither the identity domain nor its application use cases import the generic mail API.

## Transactional outbox

Creating an OTP challenge and its mail outbox row occurs in the same PostgreSQL transaction. Because the mail payload contains the OTP plaintext, the outbox stores that payload encrypted with an application key obtained outside the database. The dispatcher decrypts only while rendering/sending and redacts the encrypted payload after acceptance or challenge expiry. The OTP verification table separately stores only the keyed OTP hash.

The dispatcher leases pending rows with `FOR UPDATE SKIP LOCKED`, records each attempt, and uses `MailMessageId` as the transport idempotency key. A process crash after provider acceptance but before local receipt storage produces an indeterminate delivery; the dispatcher follows the provider-specific idempotency policy rather than sending a newly identified message.

The public OTP-request response indicates that delivery may occur; it does not synchronously wait for a mail provider or expose delivery status.

## Observability and privacy

Metrics cover accepted, retryable rejected, permanent rejected, indeterminate, attempt latency, queue age, and provider circuit state. Logs include message id, provider, attempt number, purpose, and correlation id. They exclude recipient local parts, OTP values, rendered bodies, provider credentials, and response bodies that can contain PII.

## Verification

- Contract tests run against every transport implementation.
- Scripted transports prove priority, retry, fallback, permanent rejection, and indeterminate behavior.
- Property tests verify that a stable message id survives arbitrary retry sequences.
- PostgreSQL tests prove outbox leasing, crash recovery, and atomic OTP/outbox creation.
- A recording transport allows end-to-end tests to read the OTP without sending external mail.
- SMTP integration uses a local test server and verifies the rendered text/HTML alternatives and headers.
