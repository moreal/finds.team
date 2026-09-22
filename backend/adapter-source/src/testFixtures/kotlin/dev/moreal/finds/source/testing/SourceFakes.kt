package dev.moreal.finds.source.testing

import dev.moreal.finds.source.protocol.MonotonicClock
import dev.moreal.finds.source.protocol.SuspendDelay
import dev.moreal.finds.source.protocol.WebClient
import dev.moreal.finds.source.protocol.WebRequest
import dev.moreal.finds.source.protocol.WebResult
import java.time.Duration

class ScriptedWebClient(
  results: Iterable<WebResult> = emptyList(),
) : WebClient {
  val requests = mutableListOf<WebRequest>()
  val scriptedResults = ArrayDeque(results.toList())

  override suspend fun execute(request: WebRequest): WebResult {
    requests += request
    return scriptedResults.removeFirstOrNull()
      ?: error("No scripted web result for ${request.url.value}")
  }
}

class FakeMonotonicTime(
  initialNanos: Long = 0,
) : MonotonicClock, SuspendDelay {
  var nanos: Long = initialNanos
    private set
  val waits = mutableListOf<Duration>()

  override fun nowNanos(): Long = nanos

  override suspend fun wait(duration: Duration) {
    require(!duration.isNegative) { "Fake delay cannot move backwards" }
    waits += duration
    nanos = Math.addExact(nanos, duration.toNanos())
  }

  fun advance(duration: Duration) {
    require(!duration.isNegative) { "Fake time cannot move backwards" }
    nanos = Math.addExact(nanos, duration.toNanos())
  }
}
