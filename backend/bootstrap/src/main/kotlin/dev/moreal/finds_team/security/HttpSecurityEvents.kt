package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import java.util.UUID

/** Called only on a denied HTTP path after its transaction exits. Never accepts request content. */
class HttpSecurityEvents(private val events: SecurityEventPort, private val clock: ClockPort,
  private val metrics: MeterRegistry) {
  fun denied(request: HttpServletRequest, action: SecurityEventAction) {
    val event = SecurityEvent(UUID.randomUUID(), clock.now(), action, null,
      request.uuidHeader("X-Request-ID"), request.uuidHeader("X-Correlation-ID"))
    try { events.append(event) } catch (_: Exception) {
      // Preserve 401/403/429 even when the independent store is unavailable. Log only the closed
      // category; database exceptions may include SQL bindings or attacker-controlled data.
      metrics.counter("finds.security.events", "outcome", "write_failed", "action", action.wireName).increment()
      logger.warn("Security event storage unavailable: {}", action.wireName)
    }
  }
  private fun HttpServletRequest.uuidHeader(name: String): UUID {
    val value = getHeader(name)
    return if (value != null && value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
      UUID.fromString(value) else UUID.randomUUID()
  }
  private companion object { val logger = LoggerFactory.getLogger(HttpSecurityEvents::class.java) }
}
