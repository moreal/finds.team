package dev.moreal.finds_team.config

import dev.moreal.finds_team.Application
import java.util.Base64
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.postgresql.ds.PGSimpleDataSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationDataSourceConfigurationTest {
  private val mailConfiguration = arrayOf(
    "--finds.mail.smtp.enabled=true", "--finds.mail.smtp.host=localhost", "--finds.mail.scan-interval=1h",
    "--finds.mail.encryption-key=${Base64.getEncoder().encodeToString(ByteArray(32) { 7 })}",
    "--finds.security.rp-id=finds.team", "--finds.security.allowed-origins=https://finds.team",
    "--finds.security.hash-keys.1=${Base64.getEncoder().encodeToString(ByteArray(32) { 8 })}",
  )

  @Test
  fun `production refuses a shared runtime and migrator username`() {
    PostgreSQLContainer("postgres:17-alpine").use { postgres ->
      postgres.start()
      for (profile in listOf("prod", "production")) {
        val failure = assertFailsWith<Exception> {
          SpringApplicationBuilder(Application::class.java).web(WebApplicationType.NONE).run(
            *mailConfiguration,
            "--spring.profiles.active=$profile",
            "--spring.datasource.url=${postgres.jdbcUrl}",
            "--spring.datasource.username=${postgres.username}",
            "--spring.datasource.password=${postgres.password}",
            "--spring.flyway.url=${postgres.jdbcUrl}",
            "--spring.flyway.user=${postgres.username}",
            "--spring.flyway.password=${postgres.password}",
          ).use { }
        }
        assertTrue(generateSequence(failure as Throwable?) { it.cause }.any {
          it.message?.contains("distinct migration and runtime database usernames") == true
        })
      }
    }
  }

  @Test
  fun `Flyway uses migration credentials while jooq and the primary datasource use runtime credentials`() {
    PostgreSQLContainer("postgres:17-alpine").use { postgres ->
      postgres.start()
      val admin = PGSimpleDataSource().apply {
        setURL(postgres.jdbcUrl); user = postgres.username; password = postgres.password
      }
      admin.connection.use { db ->
        db.createStatement().use { sql ->
          sql.execute("CREATE ROLE finds_migrator LOGIN PASSWORD 'test-migration-password'")
          sql.execute("CREATE ROLE finds_app LOGIN PASSWORD 'test-runtime-password'")
          sql.execute("ALTER SCHEMA public OWNER TO finds_migrator")
          sql.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC")
        }
      }
      SpringApplicationBuilder(Application::class.java).web(WebApplicationType.NONE).run(
        *mailConfiguration,
        "--spring.profiles.active=production",
        "--spring.datasource.url=${postgres.jdbcUrl}",
        "--spring.datasource.username=finds_app",
        "--spring.datasource.password=test-runtime-password",
        "--spring.flyway.url=${postgres.jdbcUrl}",
        "--spring.flyway.user=finds_migrator",
        "--spring.flyway.password=test-migration-password",
      ).use { context ->
        assertEquals("finds_app", context.getBean(DSLContext::class.java).fetchValue("SELECT current_user"))
        context.getBean(DataSource::class.java).connection.use { assertEquals("finds_app", it.metaData.userName) }
        context.getBean(Flyway::class.java).configuration.dataSource.connection.use {
          assertEquals("finds_migrator", it.metaData.userName)
        }
        assertEquals(9, context.getBean(Flyway::class.java).info().applied().size)
        val transactions = context.getBean(dev.moreal.finds.application.port.TransactionPort::class.java)
        val user = dev.moreal.finds.domain.identity.User(dev.moreal.finds.domain.identity.UserId(java.util.UUID.randomUUID()),
          dev.moreal.finds.domain.identity.EmailAddress("runtime-permissions@example.test"))
        val credentialId = dev.moreal.finds.domain.identity.CredentialId("runtime-credential")
        transactions.execute { tx ->
          tx.users.lockByEmail(user.email); tx.users.save(user)
          assertTrue(tx.credentials.insert(dev.moreal.finds.application.port.PasskeyCredential(user.id,
            dev.moreal.finds.application.port.PasskeyCredentialMaterial(credentialId, byteArrayOf(1), 0, emptySet(), false, false), java.time.Instant.now())))
          tx.credentials.remove(credentialId)
        }
        assertEquals(1, context.getBean(DSLContext::class.java).fetchValue(
          "select count(*)::int from passkey_management_references where user_id = ?", user.id.value))
      }
    }
  }
}
