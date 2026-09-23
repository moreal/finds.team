package dev.moreal.mail

interface MailTransport {
  val provider: MailProvider
  suspend fun send(message: MailMessage): MailDeliveryResult
}

sealed interface MailDeliveryResult {
  val provider: MailProvider

  data class Accepted(
    override val provider: MailProvider,
    val providerMessageId: String,
  ) : MailDeliveryResult {
    init { require(validHeaderValue(providerMessageId)) { "Invalid provider message id" } }

    override fun toString(): String = "Accepted(provider=$provider, providerMessageId=<redacted>)"
  }

  data class Rejected(
    override val provider: MailProvider,
    val failure: MailFailure,
    val retryable: Boolean,
  ) : MailDeliveryResult

  data class Indeterminate(
    override val provider: MailProvider,
    val failure: MailFailure,
  ) : MailDeliveryResult
}
