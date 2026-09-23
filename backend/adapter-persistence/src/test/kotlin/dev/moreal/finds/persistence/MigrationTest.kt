package dev.moreal.finds.persistence

import org.flywaydb.core.Flyway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest : PostgresIntegrationTest() {
  @Test
  fun `migration creates constrained schema and is idempotent`() {
    val dataSource = resetPublicSchema()
    val flyway = Flyway.configure().dataSource(dataSource).load()

    assertEquals(3, flyway.migrate().migrationsExecuted)
    assertTrue(flyway.validateWithResult().validationSuccessful)
    assertEquals(0, flyway.migrate().migrationsExecuted)

    dataSource.connection.use { connection ->
      val tables = connection.metaData.getTables(null, "public", "%", arrayOf("TABLE")).use {
        generateSequence { if (it.next()) it.getString("TABLE_NAME") else null }.toSet()
      }
      assertTrue(
        tables.containsAll(
          setOf("career_sites", "job_postings", "posting_skills", "crawl_runs", "crawl_leases", "mail_outbox", "mail_delivery_attempts"),
        ),
      )
      val constraints = connection.prepareStatement(
        "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'public'",
      ).use { statement ->
        statement.executeQuery().use {
          generateSequence { if (it.next()) it.getString(1) else null }.toSet()
        }
      }
      assertTrue("uq_career_sites_host" in constraints)
      assertTrue("uq_job_postings_site_external_key" in constraints)
      assertTrue("ck_crawl_runs_completion" in constraints)
    }
  }
}
