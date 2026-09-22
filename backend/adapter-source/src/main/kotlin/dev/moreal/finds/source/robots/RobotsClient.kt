package dev.moreal.finds.source.robots

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.protocol.MonotonicClock
import dev.moreal.finds.source.protocol.SourceProtocolSettings
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.protocol.WebFailure
import dev.moreal.finds.source.protocol.WebFailureCode
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RobotsDecision {
  data class Allowed(val sitemaps: List<SiteUrl>) : RobotsDecision

  data class Denied(
    val path: String,
    val sitemaps: List<SiteUrl>,
  ) : RobotsDecision

  data class Unavailable(val failure: WebFailure) : RobotsDecision
}

class RobotsClient(
  private val web: WebClient,
  private val settings: SourceProtocolSettings,
  private val productToken: String,
  private val clock: MonotonicClock = MonotonicClock(System::nanoTime),
) {
  private val origins = ConcurrentHashMap<SiteHost, OriginState>()

  init {
    require(Regex("[A-Za-z_-]+").matches(productToken)) {
      "Robots product token must contain only letters, underscore, or hyphen"
    }
    require(settings.maxResponseBytes >= 500 * 1024) {
      "robots.txt response limit must be at least 500 KiB"
    }
  }

  suspend fun evaluate(target: SiteUrl): RobotsDecision {
    val state = origins.computeIfAbsent(target.host) { OriginState() }
    val cached = state.mutex.withLock {
      val now = clock.nowNanos()
      state.entry?.takeIf { it.expiresAtNanos > now } ?: run {
        val fetched = fetch(target)
        val ttl = when (fetched) {
          is CacheEntry.Policy -> settings.robotsSuccessTtl
          is CacheEntry.Unavailable -> settings.robotsUnavailableTtl
        }
        fetched.withExpiry(saturatingAdd(now, ttl.toNanos())).also { stored ->
          state.entry = stored
        }
      }
    }
    return cached.decide(target, productToken)
  }

  private suspend fun fetch(target: SiteUrl): CacheEntry {
    val robotsUrl = requireUrl("https://${target.host.value}/robots.txt")
    return when (
      val result = web.execute(
        WebRequest(robotsUrl, allowedHosts = setOf(target.host), accept = "text/plain"),
      )
    ) {
      is WebResult.Failure -> CacheEntry.Unavailable(result.failure)
      is WebResult.Success -> when {
        result.response.status in 200..299 -> {
          CacheEntry.Policy(RobotsPolicy.parse(result.response.bodyText()))
        }
        result.response.status in 400..499 && result.response.status != 429 -> {
          CacheEntry.Policy(RobotsPolicy.parse(""))
        }
        else -> CacheEntry.Unavailable(
          WebFailure(
            WebFailureCode.TRANSPORT,
            "robots.txt returned HTTP ${result.response.status}",
          ),
        )
      }
    }
  }

  private sealed interface CacheEntry {
    val expiresAtNanos: Long

    fun withExpiry(value: Long): CacheEntry

    fun decide(target: SiteUrl, productToken: String): RobotsDecision

    data class Policy(
      val policy: RobotsPolicy,
      override val expiresAtNanos: Long = 0,
    ) : CacheEntry {
      override fun withExpiry(value: Long): CacheEntry = copy(expiresAtNanos = value)

      override fun decide(target: SiteUrl, productToken: String): RobotsDecision {
        val path = buildString {
          append(target.value.rawPath.ifEmpty { "/" })
          target.value.rawQuery?.let { append('?').append(it) }
        }
        val sitemaps = policy.sitemaps.mapNotNull(::parseUrl)
          .filter { it.host == target.host }
          .distinct()
        if (!policy.allows(productToken, path)) {
          return RobotsDecision.Denied(path, sitemaps)
        }
        return RobotsDecision.Allowed(sitemaps)
      }
    }

    data class Unavailable(
      val failure: WebFailure,
      override val expiresAtNanos: Long = 0,
    ) : CacheEntry {
      override fun withExpiry(value: Long): CacheEntry = copy(expiresAtNanos = value)

      override fun decide(target: SiteUrl, productToken: String): RobotsDecision =
        RobotsDecision.Unavailable(failure)
    }
  }

  private class OriginState {
    val mutex = Mutex()
    var entry: CacheEntry? = null
  }

  private companion object {
    fun parseUrl(value: String): SiteUrl? = when (val parsed = SiteUrl.parse(value)) {
      is SiteUrlResult.Valid -> parsed.url
      is SiteUrlResult.Invalid -> null
    }

    fun requireUrl(value: String): SiteUrl = requireNotNull(parseUrl(value))

    fun saturatingAdd(left: Long, right: Long): Long =
      if (right > 0 && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
  }
}
