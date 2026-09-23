package dev.moreal.mail

import java.util.Collections
import java.util.Locale
import java.util.UUID

@JvmInline
value class MailMessageId(val value: UUID) {
  override fun toString(): String = value.toString()

  companion object {
    fun new(): MailMessageId = MailMessageId(UUID.randomUUID())

    fun parse(value: String): MailMessageId {
      val uuid = UUID.fromString(value)
      require(value.lowercase(Locale.ROOT) == uuid.toString()) { "Mail message id must be a canonical UUID" }
      return MailMessageId(uuid)
    }
  }
}

data class Mailbox(val address: String, val name: String? = null) {
  init {
    require(ADDRESS.matches(address)) { "Invalid mailbox address" }
    require(name == null || validHeaderValue(name)) { "Invalid mailbox display name" }
  }

  override fun toString(): String = "Mailbox(<redacted>)"

  companion object {
    private val ADDRESS = Regex(
      "[A-Za-z0-9!#\$%&'*+/=?^_`{|}~-]+(?:\\.[A-Za-z0-9!#\$%&'*+/=?^_`{|}~-]+)*@" +
        "[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+",
    )
  }
}

class Recipients(
  to: List<Mailbox> = emptyList(),
  cc: List<Mailbox> = emptyList(),
  bcc: List<Mailbox> = emptyList(),
) {
  val to: List<Mailbox> = Collections.unmodifiableList(ArrayList(to))
  val cc: List<Mailbox> = Collections.unmodifiableList(ArrayList(cc))
  val bcc: List<Mailbox> = Collections.unmodifiableList(ArrayList(bcc))

  init {
    val all = this.to + this.cc + this.bcc
    require(all.isNotEmpty()) { "At least one recipient is required" }
    require(all.map { it.address.lowercase(Locale.ROOT) }.toSet().size == all.size) {
      "Recipient addresses must be unique"
    }
  }

  override fun equals(other: Any?): Boolean =
    other is Recipients && to == other.to && cc == other.cc && bcc == other.bcc

  override fun hashCode(): Int = 31 * (31 * to.hashCode() + cc.hashCode()) + bcc.hashCode()
}

data class MailContent(val text: String? = null, val html: String? = null) {
  init {
    require(text?.isNotBlank() == true || html?.isNotBlank() == true) {
      "At least one nonblank mail content alternative is required"
    }
    require(text == null || text.isNotBlank()) { "Text content must not be blank" }
    require(html == null || html.isNotBlank()) { "HTML content must not be blank" }
  }

  override fun toString(): String = "MailContent(<redacted>)"
}

class MailMessage(
  val id: MailMessageId,
  val from: Mailbox,
  val recipients: Recipients,
  val subject: String,
  val content: MailContent,
  tags: Set<String> = emptySet(),
  val replyTo: Mailbox? = null,
) {
  val tags: Set<String> = Collections.unmodifiableSet(LinkedHashSet(tags))

  init {
    require(validHeaderValue(subject)) { "Invalid mail subject" }
    require(this.tags.all(::validHeaderValue)) { "Invalid mail tag" }
  }

  override fun equals(other: Any?): Boolean = other is MailMessage &&
    id == other.id && from == other.from && recipients == other.recipients &&
    subject == other.subject && content == other.content && tags == other.tags && replyTo == other.replyTo

  override fun hashCode(): Int {
    var result = id.hashCode()
    result = 31 * result + from.hashCode()
    result = 31 * result + recipients.hashCode()
    result = 31 * result + subject.hashCode()
    result = 31 * result + content.hashCode()
    result = 31 * result + tags.hashCode()
    result = 31 * result + (replyTo?.hashCode() ?: 0)
    return result
  }
}

@JvmInline
value class MailProvider(val value: String) {
  init { require(validHeaderValue(value)) { "Invalid mail provider" } }

  override fun toString(): String = value
}

enum class MailFailure {
  AUTHENTICATION,
  INVALID_MESSAGE,
  INVALID_RECIPIENT,
  THROTTLED,
  SERVICE_UNAVAILABLE,
  NETWORK,
  TIMEOUT,
  UNKNOWN,
}

internal fun validHeaderValue(value: String): Boolean =
  value.isNotBlank() && value.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
