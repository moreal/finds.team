package dev.moreal.mail.smtp.internal

import jakarta.mail.Session
import java.io.OutputStream
import java.util.logging.Level
import java.util.logging.Logger
import org.eclipse.angus.mail.smtp.SMTPTransport

/** A fresh connection for each send keeps acceptance state local. Not part of the adapter's public API. */
internal class TrackedSmtpTransport(session: Session) : SMTPTransport(session, null) {
  var dataStarted = false
    private set
  var preDataReply: Int? = null
    private set

  override fun data(): OutputStream {
    dataStarted = true
    return super.data()
  }

  override fun readServerResponse(): Int {
    // Do not reuse an earlier refusal if the next read times out. Keep the code, never the response text.
    if (!dataStarted) preDataReply = null
    return super.readServerResponse().also { if (!dataStarted) preDataReply = it }
  }

  private companion object {
    // Angus 2.0.4 chooses JUL names from the concrete subclass package and adds ".protocol" for wire
    // tracing. mail.debug=false does not disable JUL, and its trace controls are private. Configure only
    // this adapter-owned namespace, once before the superclass can log. Never toggle around sends or
    // touch root/Jakarta/Angus/application loggers. Keep strong references: JUL retains loggers weakly.
    val silentLoggers: List<Logger> = listOf(
      TrackedSmtpTransport::class.java.packageName,
      "${TrackedSmtpTransport::class.java.packageName}.protocol",
    ).map { Logger.getLogger(it).apply { level = Level.OFF } }
  }
}
