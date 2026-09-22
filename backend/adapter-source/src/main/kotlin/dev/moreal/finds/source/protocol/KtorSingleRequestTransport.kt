package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

data class SourceIdentity(
  val product: String,
  val contactUrl: SiteUrl,
) {
  init {
    require(PRODUCT_PATTERN.matches(product)) {
      "Source product must contain only letters, digits, dot, underscore, or hyphen"
    }
  }

  val userAgent: String = "$product (+${contactUrl.value})"

  private companion object {
    val PRODUCT_PATTERN = Regex("[A-Za-z0-9._-]+")
  }
}

class KtorSingleRequestTransport(
  private val client: HttpClient,
  private val settings: SourceProtocolSettings,
  private val identity: SourceIdentity,
) : SingleRequestTransport, AutoCloseable {
  override suspend fun execute(request: TransportRequest): TransportResponse {
    val response = client.get(request.url.value.toString()) {
      header(HttpHeaders.UserAgent, identity.userAgent)
      request.accept?.let { header(HttpHeaders.Accept, it) }
    }
    val body = response.bodyAsChannel()
      .readRemaining(settings.maxResponseBytes.toLong() + 1)
      .readByteArray()
    val finalUrl = parseUrl(response.call.request.url.toString())

    return TransportResponse(
      status = response.status.value,
      finalUrl = finalUrl,
      headers = response.headers.entries()
        .associate { (name, values) -> name.lowercase() to values.toList() },
      body = body,
    )
  }

  override fun close() {
    client.close()
  }

  companion object {
    fun create(
      settings: SourceProtocolSettings,
      identity: SourceIdentity,
    ): KtorSingleRequestTransport {
      val client = HttpClient(CIO) {
        configureSourceTransport(settings)
        engine {
          requestTimeout = settings.siteTimeout.toMillis()
          endpoint.connectTimeout = settings.connectTimeout.toMillis()
          endpoint.socketTimeout = settings.requestTimeout.toMillis()
        }
      }
      return KtorSingleRequestTransport(client, settings, identity)
    }

    private fun parseUrl(value: String): SiteUrl = when (val parsed = SiteUrl.parse(value)) {
      is SiteUrlResult.Valid -> parsed.url
      is SiteUrlResult.Invalid -> error("Ktor returned an unsafe final URL: ${parsed.reason}")
    }
  }
}

fun HttpClientConfig<*>.configureSourceTransport(settings: SourceProtocolSettings) {
  followRedirects = false
  expectSuccess = false
  install(HttpTimeout) {
    connectTimeoutMillis = settings.connectTimeout.toMillis()
    requestTimeoutMillis = settings.requestTimeout.toMillis()
    socketTimeoutMillis = settings.requestTimeout.toMillis()
  }
  install(ContentEncoding) {
    gzip()
    deflate()
  }
}
