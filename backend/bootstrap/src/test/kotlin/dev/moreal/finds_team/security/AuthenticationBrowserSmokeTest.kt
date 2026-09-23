package dev.moreal.finds_team.security

import dev.moreal.finds.notification.MailOutboxDispatcher
import dev.moreal.mail.testing.RecordingMailTransport
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.AfterAll

/** Opt-in real browser gate: no OTP HTTP endpoint, persistent certificate, or external mail. */
@Tag("browser")
class AuthenticationBrowserSmokeTest : OtpHttpSupport() {
  private val temporary = Files.createTempDirectory("finds-passkey-browser-")
  @AfterAll fun removeTestCertificate() {
    Files.deleteIfExists(temporary.resolve("localhost.p12"))
    Files.deleteIfExists(temporary)
  }
  private var port = 0
  override fun extraArguments(): List<String> {
    port = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val keystore = temporary.resolve("localhost.p12")
    val keytool = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
      "-genkeypair", "-alias", "localhost", "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "1",
      "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
      "-keystore", keystore.toString(), "-storepass", "test-only", "-noprompt").redirectErrorStream(true).start()
    try {
      assertTrue(keytool.waitFor(20, TimeUnit.SECONDS), "test certificate generation timed out")
      assertEquals(0, keytool.exitValue(), "test certificate generation failed")
    } finally { keytool.destroyForcibly() }
    return listOf("--server.address=127.0.0.1", "--server.port=$port", "--server.ssl.enabled=true",
      "--server.ssl.key-store=$keystore", "--server.ssl.key-store-type=PKCS12", "--server.ssl.key-store-password=test-only",
      "--finds.security.allowed-origins=https://localhost:$port")
  }

  @Test fun `Chromium resident Passkey enrolls signs in and recovers over HTTPS`() {
    val script = Path.of("../../frontend/e2e/authentication-smoke.mjs").toAbsolutePath().normalize()
    val email = email()
    val process = ProcessBuilder("node", script.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val worker = Executors.newSingleThreadExecutor()
    try {
      val conversation = worker.submit<Boolean> {
        process.outputStream.bufferedWriter().use { input ->
          val output = process.inputStream.bufferedReader()
          input.write(json.writeValueAsString(mapOf("origin" to "https://localhost:$port", "email" to email.value)))
          input.newLine(); input.flush()
          listOf("enrollment", "recovery").forEach { purpose ->
            assertEquals("otp:$purpose", output.readLine(), "browser must request delivery before reading mail")
            val otp = runBlocking {
              context.getBean(MailOutboxDispatcher::class.java).dispatch()
              val mail = context.getBean(RecordingMailTransport::class.java).messages()
                .last { it.recipients.to.single().address == email.value && purpose.uppercase() in it.tags }
              Regex("[0-9]{8}").find(mail.content.text!!)!!.value
            }
            // Secret travels only through this test process pipe; never console, URL, or HTTP fixture routes.
            input.write(otp); input.newLine(); input.flush()
          }
          assertEquals("passed", output.readLine(), "browser lifecycle assertions must finish")
          true
        }
      }
      assertTrue(conversation.get(90, TimeUnit.SECONDS))
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "browser worker did not exit")
      assertEquals(0, process.exitValue())
    } finally {
      process.toHandle().descendants().use { children -> children.forEach { it.destroyForcibly() } }
      process.destroyForcibly(); worker.shutdownNow()
      assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
    }
  }
}
