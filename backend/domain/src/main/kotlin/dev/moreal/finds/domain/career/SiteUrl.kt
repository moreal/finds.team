package dev.moreal.finds.domain.career

import java.net.IDN
import java.net.URI

sealed interface SiteUrlResult {
  data class Valid(val url: SiteUrl) : SiteUrlResult

  data class Invalid(val reason: String) : SiteUrlResult
}

@ConsistentCopyVisibility
data class SiteUrl private constructor(
  val value: URI,
  val host: SiteHost,
) {
  companion object {
    fun parse(value: String): SiteUrlResult = runCatching {
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

      val normalizedHost = IDN.toASCII(
        authority.lowercase(),
        IDN.USE_STD3_ASCII_RULES,
      )
      val siteHost = SiteHost(normalizedHost)

      val normalizedPath = parsed.path.orEmpty().let { path ->
        if (path.length > 1) path.trimEnd('/') else path
      }
      val canonical = URI(
        "https",
        null,
        normalizedHost,
        -1,
        normalizedPath,
        parsed.query,
        null,
      )
      SiteUrl(canonical, siteHost)
    }.fold(
      onSuccess = SiteUrlResult::Valid,
      onFailure = { error ->
        SiteUrlResult.Invalid(error.message ?: "Invalid career-site URL")
      },
    )
  }
}

@JvmInline
value class SiteHost(val value: String) {
  init {
    val normalized = runCatching {
      IDN.toASCII(value.lowercase(), IDN.USE_STD3_ASCII_RULES)
    }.getOrNull()
    require(value.isNotBlank() && value == normalized) {
      "Site host must be a normalized ASCII DNS name"
    }
    require('.' in value && ':' !in value && '%' !in value) {
      "Site host must be a public DNS name"
    }
    require(!IPV4_PATTERN.matches(value)) { "Site host must not be an IP address" }
  }

  private companion object {
    val IPV4_PATTERN = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
  }
}
