package dev.moreal.finds_team.graphql

import dev.moreal.finds_team.config.FindsProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

@Component
class GraphqlRequestLimitFilter(
  properties: FindsProperties,
) : OncePerRequestFilter() {
  private val maximumBytes = properties.graphql.maximumRequestBytes

  override fun shouldNotFilter(request: HttpServletRequest): Boolean =
    request.method != "POST" || request.requestURI != "/graphql"

  override fun doFilterInternal(
    request: HttpServletRequest,
    response: HttpServletResponse,
    filterChain: FilterChain,
  ) {
    if (request.contentLengthLong > maximumBytes) {
      reject(response)
      return
    }
    val body = request.inputStream.use { it.readNBytes(maximumBytes + 1) }
    if (body.size > maximumBytes) {
      reject(response)
      return
    }
    filterChain.doFilter(CachedBodyRequest(request, body), response)
  }

  private fun reject(response: HttpServletResponse) {
    response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = StandardCharsets.UTF_8.name()
    response.writer.write("{\"errors\":[{\"message\":\"GraphQL request body is too large\"}]}")
  }

  private class CachedBodyRequest(
    request: HttpServletRequest,
    private val body: ByteArray,
  ) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(body)

    override fun getReader(): BufferedReader = BufferedReader(
      InputStreamReader(inputStream, characterEncoding?.let(Charset::forName) ?: Charsets.UTF_8),
    )
  }

  private class ByteArrayServletInputStream(body: ByteArray) : ServletInputStream() {
    private val source = ByteArrayInputStream(body)

    override fun read(): Int = source.read()

    override fun isFinished(): Boolean = source.available() == 0

    override fun isReady(): Boolean = true

    override fun setReadListener(readListener: ReadListener) {
      if (isFinished) readListener.onAllDataRead() else readListener.onDataAvailable()
    }
  }
}
