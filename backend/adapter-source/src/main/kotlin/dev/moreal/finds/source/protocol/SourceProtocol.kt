package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration

data class SourceProtocolSettings(
  val connectTimeout: Duration = Duration.ofSeconds(10),
  val requestTimeout: Duration = Duration.ofSeconds(30),
  val siteTimeout: Duration = Duration.ofMinutes(5),
  val maxResponseBytes: Int = 5 * 1024 * 1024,
  val maxRedirects: Int = 5,
  val robotsSuccessTtl: Duration = Duration.ofHours(24),
  val robotsUnavailableTtl: Duration = Duration.ofMinutes(5),
  val maxSitemapDepth: Int = 3,
  val maxSitemapDocuments: Int = 100,
  val maxSitemapUrls: Int = 50_000,
  val minimumHostSpacing: Duration = Duration.ofMillis(500),
) {
  init {
    requirePositiveBounded(connectTimeout, Duration.ofMinutes(1), "Connect timeout")
    requirePositiveBounded(requestTimeout, Duration.ofMinutes(5), "Request timeout")
    requirePositiveBounded(siteTimeout, Duration.ofHours(1), "Site timeout")
    require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) {
      "Response byte limit must be between 1 and $MAX_RESPONSE_BYTES"
    }
    require(maxRedirects in 0..10) { "Redirect limit must be between 0 and 10" }
    requirePositiveBounded(robotsSuccessTtl, Duration.ofDays(7), "Robots success TTL")
    requirePositiveBounded(
      robotsUnavailableTtl,
      Duration.ofHours(1),
      "Robots unavailable TTL",
    )
    require(maxSitemapDepth in 1..10) { "Sitemap depth limit must be between 1 and 10" }
    require(maxSitemapDocuments in 1..1_000) {
      "Sitemap document limit must be between 1 and 1000"
    }
    require(maxSitemapUrls in 1..1_000_000) {
      "Sitemap URL limit must be between 1 and 1000000"
    }
    requirePositiveBounded(
      minimumHostSpacing,
      Duration.ofMinutes(1),
      "Minimum host spacing",
    )
  }

  private companion object {
    const val MAX_RESPONSE_BYTES = 50 * 1024 * 1024

    fun requirePositiveBounded(value: Duration, maximum: Duration, name: String) {
      require(!value.isZero && !value.isNegative && value <= maximum) {
        "$name must be positive and no greater than $maximum"
      }
    }
  }
}

data class WebRequest(
  val url: SiteUrl,
  val allowedHosts: Set<SiteHost> = setOf(url.host),
  val accept: String? = null,
) {
  init {
    require(allowedHosts.isNotEmpty()) { "Allowed hosts must not be empty" }
    require(url.host in allowedHosts) { "Request host must be explicitly allowed" }
    require(accept == null || accept.isNotBlank()) { "Accept header must not be blank" }
  }
}

class WebResponse(
  val status: Int,
  val finalUrl: SiteUrl,
  headers: Map<String, List<String>> = emptyMap(),
  body: ByteArray,
) {
  val headers: Map<String, List<String>> = headers
    .mapKeys { (name, _) -> name.lowercase() }
    .mapValues { (_, values) -> values.toList() }
  private val content: ByteArray = body.copyOf()
  val body: ByteArray
    get() = content.copyOf()

  init {
    require(status in 100..599) { "HTTP status must be between 100 and 599" }
  }

  fun bodyText(charset: Charset = StandardCharsets.UTF_8): String =
    content.toString(charset)
}

enum class WebFailureCode {
  INVALID_DESTINATION,
  DNS_REJECTED,
  ROBOTS_DENIED,
  TIMEOUT,
  TOO_MANY_REDIRECTS,
  RESPONSE_TOO_LARGE,
  TRANSPORT,
}

data class WebFailure(
  val code: WebFailureCode,
  val message: String,
) {
  init {
    require(message.isNotBlank()) { "Web failure message must not be blank" }
    require(message.length <= 1_000) { "Web failure message must not exceed 1000 characters" }
    require('\n' !in message && '\r' !in message) { "Web failure message must be one line" }
  }
}

sealed interface WebResult {
  data class Success(val response: WebResponse) : WebResult

  data class Failure(val failure: WebFailure) : WebResult
}

fun interface WebClient {
  suspend fun execute(request: WebRequest): WebResult
}

fun interface MonotonicClock {
  fun nowNanos(): Long
}

fun interface SuspendDelay {
  suspend fun wait(duration: Duration)
}
