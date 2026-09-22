package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import dev.moreal.finds.source.testing.FakeMonotonicTime
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SafeKtorWebClientTest {
  @Test
  fun `ktor transport disables redirects identifies itself and bounds body reads`() = runTest {
    val observedUserAgents = mutableListOf<String?>()
    val engine = MockEngine { request ->
      observedUserAgents += request.headers[HttpHeaders.UserAgent]
      when (request.url.encodedPath) {
        "/large" -> respond("a".repeat(101), HttpStatusCode.OK)
        "/gzip" -> respond(
          ByteReadChannel(gzip("a".repeat(1_000))),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentEncoding, "gzip"),
        )
        else -> respond(
          "redirect",
          HttpStatusCode.Found,
          headersOf(HttpHeaders.Location, "/next"),
        )
      }
    }
    val settings = SourceProtocolSettings(maxResponseBytes = 100)
    val ktor = KtorSingleRequestTransport(
      client = HttpClient(engine) { configureSourceTransport(settings) },
      settings = settings,
      identity = SourceIdentity("finds.team", url("https://finds.team/contact")),
    )

    val redirect = ktor.execute(TransportRequest(url("https://jobs.example/start"), "text/html"))
    val oversized = ktor.execute(TransportRequest(url("https://jobs.example/large"), null))
    val decompressed = ktor.execute(TransportRequest(url("https://jobs.example/gzip"), null))

    assertEquals(302, redirect.status)
    assertEquals("/next", redirect.headers["location"]?.single())
    assertEquals(101, oversized.body.size)
    assertEquals(101, decompressed.body.size)
    assertEquals(
      listOf<String?>(
        "finds.team (+https://finds.team/contact)",
        "finds.team (+https://finds.team/contact)",
        "finds.team (+https://finds.team/contact)",
      ),
      observedUserAgents,
    )
  }

  @Test
  fun `validates every redirect and retains the original host allowlist`() = runTest {
    val transport = ScriptedTransport(
      listOf(
        TransportResponse(302, url("https://jobs.example/start"), mapOf("location" to listOf("/next"))),
        TransportResponse(200, url("https://jobs.example/next"), body = "ok".encodeToByteArray()),
      ),
    )
    val client = client(transport)

    val result = assertIs<WebResult.Success>(
      client.execute(WebRequest(url("https://jobs.example/start"))),
    )

    assertEquals("ok", result.response.bodyText())
    assertEquals(
      listOf("https://jobs.example/start", "https://jobs.example/next"),
      transport.requests.map { it.url.value.toString() },
    )
  }

  @Test
  fun `rejects cross-host and excessive redirects before requesting target`() = runTest {
    val crossHost = ScriptedTransport(
      listOf(
        TransportResponse(
          302,
          url("https://jobs.example/start"),
          mapOf("location" to listOf("https://evil.example/next")),
        ),
      ),
    )
    val rejected = assertIs<WebResult.Failure>(
      client(crossHost).execute(WebRequest(url("https://jobs.example/start"))),
    )
    assertEquals(WebFailureCode.INVALID_DESTINATION, rejected.failure.code)
    assertEquals(1, crossHost.requests.size)

    val looping = ScriptedTransport(
      List(11) {
        TransportResponse(302, url("https://jobs.example/$it"), mapOf("location" to listOf("/${it + 1}")))
      },
    )
    val tooMany = assertIs<WebResult.Failure>(
      client(looping, SourceProtocolSettings(maxRedirects = 2))
        .execute(WebRequest(url("https://jobs.example/0"))),
    )
    assertEquals(WebFailureCode.TOO_MANY_REDIRECTS, tooMany.failure.code)
    assertEquals(3, looping.requests.size)
  }

  @Test
  fun `serializes same-host calls and waits between request starts`() = runTest {
    val time = FakeMonotonicTime()
    val transport = ScriptedTransport(
      listOf(
        TransportResponse(200, url("https://jobs.example/a")),
        TransportResponse(200, url("https://jobs.example/b")),
      ),
      time,
    )
    val client = client(transport, time = time)

    val first = async { client.execute(WebRequest(url("https://jobs.example/a"))) }
    val second = async { client.execute(WebRequest(url("https://jobs.example/b"))) }
    first.await()
    second.await()

    assertEquals(listOf(0L, 500_000_000L), transport.startedAtNanos)
    assertEquals(listOf(Duration.ofMillis(500)), time.waits)
  }

  @Test
  fun `different hosts do not share serialization state`() = runTest {
    val time = FakeMonotonicTime()
    val transport = ScriptedTransport(
      listOf(
        TransportResponse(200, url("https://jobs.example/a")),
        TransportResponse(200, url("https://api.example/b")),
      ),
      time,
    )
    val client = client(transport, time = time)

    client.execute(WebRequest(url("https://jobs.example/a")))
    client.execute(WebRequest(url("https://api.example/b")))

    assertEquals(listOf(0L, 0L), transport.startedAtNanos)
    assertTrue(time.waits.isEmpty())
  }

  @Test
  fun `transport failures are sanitized and cancellations propagate`() = runTest {
    val transport = ScriptedTransport(failure = IllegalStateException("socket\nsecret"))
    val result = assertIs<WebResult.Failure>(
      client(transport).execute(WebRequest(url("https://jobs.example"))),
    )

    assertEquals(WebFailureCode.TRANSPORT, result.failure.code)
    assertEquals("socket secret", result.failure.message)

    val timeout = assertIs<WebResult.Failure>(
      client(ScriptedTransport(failure = SocketTimeoutException("slow")))
        .execute(WebRequest(url("https://jobs.example"))),
    )
    assertEquals(WebFailureCode.TIMEOUT, timeout.failure.code)

    assertFailsWith<CancellationException> {
      client(ScriptedTransport(failure = CancellationException("stopped")))
        .execute(WebRequest(url("https://jobs.example")))
    }
  }

  private fun client(
    transport: SingleRequestTransport,
    settings: SourceProtocolSettings = SourceProtocolSettings(),
    time: FakeMonotonicTime = FakeMonotonicTime(),
  ): SafeKtorWebClient = SafeKtorWebClient(
    transport = transport,
    destinationPolicy = DestinationPolicy(
      HostResolver { listOf(InetAddress.getByName("93.184.216.34")) },
    ),
    settings = settings,
    clock = time,
    delay = time,
  )

  private class ScriptedTransport(
    responses: List<TransportResponse> = emptyList(),
    private val time: MonotonicClock = MonotonicClock { 0 },
    private val failure: Exception? = null,
  ) : SingleRequestTransport {
    val requests = mutableListOf<TransportRequest>()
    val startedAtNanos = mutableListOf<Long>()
    private val responses = ArrayDeque(responses)

    override suspend fun execute(request: TransportRequest): TransportResponse {
      requests += request
      startedAtNanos += time.nowNanos()
      failure?.let { throw it }
      return responses.removeFirstOrNull() ?: error("Missing response")
    }
  }

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url

  private fun gzip(value: String): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(value.encodeToByteArray()) }
    return output.toByteArray()
  }
}
