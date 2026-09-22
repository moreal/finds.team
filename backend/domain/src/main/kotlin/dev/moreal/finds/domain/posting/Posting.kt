package dev.moreal.finds.domain.posting

import dev.moreal.finds.domain.career.CareerSiteId
import java.net.IDN
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

sealed interface PostingUrlResult {
  data class Valid(val url: PostingUrl) : PostingUrlResult

  data class Invalid(val reason: String) : PostingUrlResult
}

@ConsistentCopyVisibility
data class PostingUrl private constructor(val value: URI) {
  companion object {
    fun parse(value: String): PostingUrlResult = runCatching {
      val parsed = URI(value)
      require(parsed.isAbsolute) { "URL must be absolute" }
      require(parsed.scheme.equals("https", ignoreCase = true)) {
        "URL scheme must be HTTPS"
      }
      require(parsed.rawUserInfo == null) { "URL must not contain user information" }
      require(parsed.port == -1) { "URL must not contain an explicit port" }
      require(parsed.rawFragment == null) { "URL must not contain a fragment" }

      val authority = requireNotNull(parsed.rawAuthority) { "URL must contain a host" }
      require('@' !in authority) { "URL must not contain user information" }
      require(':' !in authority && '%' !in authority) {
        "URL host must be a DNS name without a port"
      }
      val host = IDN.toASCII(authority.lowercase(), IDN.USE_STD3_ASCII_RULES)
      require(host.isNotBlank() && '.' in host) { "URL host must be a public DNS name" }
      require(!IPV4_PATTERN.matches(host)) { "URL host must not be an IP address" }

      PostingUrl(
        URI(
          "https",
          null,
          host,
          -1,
          parsed.path.orEmpty(),
          parsed.query,
          null,
        ),
      )
    }.fold(
      onSuccess = PostingUrlResult::Valid,
      onFailure = { error -> PostingUrlResult.Invalid(error.message ?: "Invalid posting URL") },
    )

    private val IPV4_PATTERN = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
  }
}

@JvmInline
value class JobPostingId(val value: Long) {
  init {
    require(value > 0) { "Job-posting ID must be positive" }
  }
}

enum class PostingStatus {
  OPEN,
  CLOSED,
}

data class RawPosting(
  val externalKey: String,
  val title: String,
  val descriptionText: String,
  val canonicalUrl: PostingUrl,
  val employmentHint: String? = null,
  val locationHint: String? = null,
  val remoteHint: String? = null,
  val sourceUpdatedAt: Instant? = null,
) {
  init {
    require(externalKey.isNotBlank()) { "External key must not be blank" }
    require(externalKey == externalKey.trim()) { "External key must be trimmed" }
    require(title.isNotBlank()) { "Posting title must not be blank" }
  }

  fun contentHash(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(
      title.normalizedContent(),
      descriptionText.normalizedContent(),
      employmentHint?.normalizedContent(),
      locationHint?.normalizedContent(),
      remoteHint?.normalizedContent(),
    ).forEach { field -> digest.addLengthPrefixed(field) }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
  }

  private fun String.normalizedContent(): String =
    replace("\r\n", "\n").replace('\r', '\n').trim()

  private fun MessageDigest.addLengthPrefixed(value: String?) {
    if (value == null) {
      update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(-1).array())
      return
    }
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
    update(bytes)
  }
}

data class JobPosting(
  val id: JobPostingId,
  val careerSiteId: CareerSiteId,
  val raw: RawPosting,
  val contentHash: String,
  val status: PostingStatus,
  val consecutiveMisses: Int,
  val firstSeenAt: Instant,
  val lastSeenAt: Instant,
  val updatedAt: Instant,
  val closedAt: Instant?,
) {
  init {
    require(CONTENT_HASH_PATTERN.matches(contentHash)) {
      "Content hash must be lowercase SHA-256"
    }
    require(consecutiveMisses >= 0) { "Consecutive misses must not be negative" }
    require(!lastSeenAt.isBefore(firstSeenAt)) {
      "Last-seen time must not precede first-seen time"
    }
    require(!updatedAt.isBefore(firstSeenAt)) {
      "Updated time must not precede first-seen time"
    }
    when (status) {
      PostingStatus.OPEN -> require(closedAt == null) {
        "Open posting must not have a closed time"
      }
      PostingStatus.CLOSED -> require(closedAt != null) {
        "Closed posting must have a closed time"
      }
    }
  }

  private companion object {
    val CONTENT_HASH_PATTERN = Regex("[0-9a-f]{64}")
  }
}
