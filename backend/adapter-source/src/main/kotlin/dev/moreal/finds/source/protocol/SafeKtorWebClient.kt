package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import java.net.URI
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TransportRequest(
  val url: SiteUrl,
  val accept: String?,
)

data class TransportResponse(
  val status: Int,
  val finalUrl: SiteUrl,
  val headers: Map<String, List<String>> = emptyMap(),
  val body: ByteArray = byteArrayOf(),
) {
  init {
    require(status in 100..599) { "HTTP status must be between 100 and 599" }
  }
}

fun interface SingleRequestTransport {
  suspend fun execute(request: TransportRequest): TransportResponse
}

class SafeKtorWebClient(
  private val transport: SingleRequestTransport,
  private val destinationPolicy: DestinationPolicy,
  private val settings: SourceProtocolSettings,
  private val clock: MonotonicClock = MonotonicClock(System::nanoTime),
  private val delay: SuspendDelay = SuspendDelay { duration ->
    delay(duration.toMillis())
  },
) : WebClient {
  private val hostStates = ConcurrentHashMap<SiteHost, HostState>()

  override suspend fun execute(request: WebRequest): WebResult {
    var currentUrl = request.url
    var followedRedirects = 0
    while (true) {
      when (val decision = destinationPolicy.evaluate(currentUrl, request.allowedHosts)) {
        DestinationDecision.Allowed -> Unit
        is DestinationDecision.Rejected -> return WebResult.Failure(decision.failure)
      }

      val response = try {
        executeSerialized(currentUrl.host) {
          transport.execute(TransportRequest(currentUrl, request.accept))
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        val code = if (
          error is SocketTimeoutException ||
          error is ConnectTimeoutException ||
          error is HttpRequestTimeoutException
        ) {
          WebFailureCode.TIMEOUT
        } else {
          WebFailureCode.TRANSPORT
        }
        return WebResult.Failure(
          WebFailure(code, error.safeMessage()),
        )
      }

      if (response.finalUrl != currentUrl) {
        return WebResult.Failure(
          WebFailure(
            WebFailureCode.INVALID_DESTINATION,
            "Transport followed an unvalidated redirect",
          ),
        )
      }

      if (response.body.size > settings.maxResponseBytes) {
        return WebResult.Failure(
          WebFailure(
            WebFailureCode.RESPONSE_TOO_LARGE,
            "Response exceeded ${settings.maxResponseBytes} bytes",
          ),
        )
      }
      if (response.status !in REDIRECT_STATUSES) {
        return WebResult.Success(
          WebResponse(
            status = response.status,
            finalUrl = response.finalUrl,
            headers = response.headers,
            body = response.body,
          ),
        )
      }

      val location = response.header("location")
        ?: return WebResult.Failure(
          WebFailure(WebFailureCode.INVALID_DESTINATION, "Redirect response had no Location"),
        )
      if (followedRedirects >= settings.maxRedirects) {
        return WebResult.Failure(
          WebFailure(WebFailureCode.TOO_MANY_REDIRECTS, "Redirect limit exceeded"),
        )
      }
      currentUrl = resolveRedirect(currentUrl, location)
        ?: return WebResult.Failure(
          WebFailure(WebFailureCode.INVALID_DESTINATION, "Redirect target was not a safe HTTPS URL"),
        )
      followedRedirects += 1
    }
  }

  private suspend fun <T> executeSerialized(host: SiteHost, action: suspend () -> T): T {
    val state = hostStates.computeIfAbsent(host) { HostState() }
    return state.mutex.withLock {
      state.lastStartedAtNanos?.let { lastStarted ->
        val elapsed = (clock.nowNanos() - lastStarted).coerceAtLeast(0)
        val remaining = settings.minimumHostSpacing.toNanos() - elapsed
        if (remaining > 0) {
          delay.wait(Duration.ofNanos(remaining))
        }
      }
      state.lastStartedAtNanos = clock.nowNanos()
      action()
    }
  }

  private fun resolveRedirect(source: SiteUrl, location: String): SiteUrl? {
    val resolved = runCatching { source.value.resolve(URI(location)) }.getOrNull() ?: return null
    return when (val parsed = SiteUrl.parse(resolved.toString())) {
      is SiteUrlResult.Valid -> parsed.url
      is SiteUrlResult.Invalid -> null
    }
  }

  private fun TransportResponse.header(name: String): String? =
    headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
      ?.value
      ?.firstOrNull()

  private class HostState {
    val mutex = Mutex()
    var lastStartedAtNanos: Long? = null
  }

  private companion object {
    val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
  }
}
