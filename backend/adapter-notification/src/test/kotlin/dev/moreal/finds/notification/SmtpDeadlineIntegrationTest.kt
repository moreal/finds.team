package dev.moreal.finds.notification

import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.testing.FakeTransaction
import dev.moreal.finds.application.testing.MailOutboxFake
import dev.moreal.finds.domain.identity.EmailAddress
import dev.moreal.finds.persistence.AesGcmMailPayloadCrypto
import dev.moreal.mail.*
import dev.moreal.mail.pool.PriorityMailTransport
import dev.moreal.mail.retry.RetryMailTransport
import dev.moreal.mail.retry.RetryPolicy
import dev.moreal.mail.smtp.SmtpMailTransport
import dev.moreal.mail.smtp.SmtpSettings
import dev.moreal.mail.smtp.SmtpTlsMode
import dev.moreal.mail.testing.RecordingMailTransport
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

class SmtpDeadlineIntegrationTest {
  @Test
  fun `stalled SMTP submission respects dispatch deadline and next mail proceeds without fallback`() = runBlocking {
    StallingSmtpPeer().use { peer ->
      val clock = ClockPort(Instant::now)
      val expiresAt = clock.now().plusSeconds(60)
      val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 7 }))
      val outbox = MailOutboxFake(crypto)
      val transaction = FakeTransaction()
      val ids = listOf(DeliveryRequestId(UUID.randomUUID()), DeliveryRequestId(UUID.randomUUID()))
      ids.forEach { id ->
        transaction.execute { MailVerificationCodeNotifier(clock).deliver(it, EmailAddress("private@example.test"),
          VerificationPurpose.ENROLLMENT, VerificationCode("01234567"), expiresAt, id) }
      }
      transaction.outboxMessages.forEach { outbox.enqueue(it.metadata, it.plaintext, it.createdAt) }
      val fallback = RecordingMailTransport(MailProvider("fallback"))
      val smtp = RetryMailTransport(ExpiringMailTransport(SmtpMailTransport(peer.settings), clock),
        RetryPolicy(3, 10.milliseconds, 10.milliseconds, 0.0))
      val pool = PriorityMailTransport(listOf(PriorityMailTransport.Entry(smtp, 100),
        PriorityMailTransport.Entry(fallback, 50)))
      val outcomes = mutableListOf<DispatchOutcome>()
      val dispatcher = MailOutboxDispatcher(outbox, crypto, pool, Mailbox("verify@finds.team"), clock,
        MailDispatchPolicy(batchSize = 2, sendTimeout = Duration.ofMillis(500), leaseDuration = Duration.ofSeconds(6)),
        MailDispatchMetrics(outcomes::add))

      val started = TimeSource.Monotonic.markNow()
      assertEquals(2, dispatcher.dispatch())
      val elapsed = started.elapsedNow()
      assertTrue(elapsed < 1500.milliseconds, "500ms dispatch deadline returned after $elapsed")
      assertTrue(peer.firstSocketClosed.get(1, TimeUnit.SECONDS), "Cancellation must close the submitted socket")
      val messages = peer.messages.get(1, TimeUnit.SECONDS)
      assertEquals(2, messages.size)
      ids.zip(messages).forEach { (id, wire) -> assertContains(wire, "<${id.value}@mail.invalid>") }
      assertEquals(listOf(DispatchOutcome.INDETERMINATE, DispatchOutcome.ACCEPTED), outcomes)
      assertEquals(ids.map { MailMessageId(it.value) }, outbox.attempts.map { it.messageId })
      assertEquals(MailFailure.TIMEOUT, assertIs<MailDeliveryResult.Indeterminate>(outbox.attempts[0].result).failure)
      assertIs<MailDeliveryResult.Accepted>(outbox.attempts[1].result)
      assertTrue(fallback.messages().isEmpty(), "Possible SMTP acceptance must never fall back")
      assertEquals(0, dispatcher.dispatch())
      assertEquals(1, outbox.redactExpired(expiresAt)) // Only the ambiguous first payload remains held.
    }
  }
}

/** The first connection receives the full message but withholds its receipt; the second accepts. */
private class StallingSmtpPeer : AutoCloseable {
  private val server = ServerSocket(0, 2, InetAddress.getLoopbackAddress()).apply { soTimeout = 5_000 }
  @Volatile private var socket: Socket? = null
  val firstSocketClosed = CompletableFuture<Boolean>()
  val messages = CompletableFuture<List<String>>()
  val settings = SmtpSettings(server.inetAddress.hostAddress, server.localPort, SmtpTlsMode.NONE,
    connectTimeout = 10.seconds, readTimeout = 10.seconds)
  private val worker = thread(isDaemon = true, name = "smtp-deadline-peer") {
    try {
      val received = mutableListOf<String>()
      repeat(2) { index ->
        server.accept().use { client ->
          socket = client
          client.soTimeout = 3_000 // Safety bound; longer than the asserted dispatcher deadline.
          val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
          val output = client.getOutputStream().bufferedWriter(Charsets.US_ASCII)
          fun reply(line: String) { output.write("$line\r\n"); output.flush() }
          reply("220 localhost deadline test")
          while (true) {
            val line = input.readLine() ?: break
            when {
              line.startsWith("EHLO") -> reply("250 localhost")
              line.startsWith("MAIL FROM:") || line.startsWith("RCPT TO:") -> reply("250 OK")
              line == "DATA" -> {
                reply("354 Send message")
                val body = StringBuilder()
                while (true) {
                  val data = input.readLine() ?: error("Incomplete submission")
                  if (data == ".") break
                  body.append(data).append('\n')
                }
                received += body.toString()
                if (index == 0) {
                  firstSocketClosed.complete(try { input.read() == -1 } catch (_: SocketTimeoutException) { false })
                  break
                }
                reply("250 accepted")
              }
              line == "QUIT" -> { reply("221 bye"); break }
              else -> error("Unexpected SMTP command")
            }
          }
        }
      }
      messages.complete(received)
    } catch (failure: Exception) {
      firstSocketClosed.completeExceptionally(failure)
      messages.completeExceptionally(failure)
    }
  }

  override fun close() {
    socket?.close()
    server.close()
    worker.join(4_000)
    check(!worker.isAlive) { "SMTP deadline peer did not stop" }
  }
}
