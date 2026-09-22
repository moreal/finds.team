package dev.moreal.finds.persistence

import dev.moreal.finds.persistence.jooq.generated.tables.references.CAREER_SITES
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_LEASES
import dev.moreal.finds.persistence.jooq.generated.tables.references.CRAWL_RUNS
import dev.moreal.finds.persistence.jooq.generated.tables.references.JOB_POSTINGS
import dev.moreal.finds.persistence.jooq.generated.tables.references.POSTING_SKILLS
import org.flywaydb.core.Flyway
import org.jooq.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GeneratedSchemaTest : PostgresIntegrationTest() {
  @Test
  fun `generated types expose every migrated table and required identity columns`() {
    assertEquals(
      setOf("career_sites", "crawl_leases", "crawl_runs", "job_postings", "posting_skills"),
      generatedTables().map(Table<*>::getName).toSet(),
    )
    assertNotNull(CAREER_SITES.ID)
    assertNotNull(CAREER_SITES.HOST)
    assertNotNull(JOB_POSTINGS.CAREER_SITE_ID)
    assertNotNull(JOB_POSTINGS.EXTERNAL_KEY)
    assertNotNull(CRAWL_RUNS.OUTCOME)
    assertNotNull(CRAWL_LEASES.EXPIRES_AT)
  }

  @Test
  fun `generated columns equal migrated PostgreSQL metadata`() {
    val dataSource = resetPublicSchema()
    Flyway.configure().dataSource(dataSource).load().migrate()

    dataSource.connection.use { connection ->
      generatedTables().forEach { table ->
        val actual = connection.metaData.getColumns(null, "public", table.name, "%").use {
          generateSequence { if (it.next()) it.getString("COLUMN_NAME") else null }.toSet()
        }
        assertEquals(table.fields().map { it.name }.toSet(), actual, table.name)
      }
    }
  }

  private fun generatedTables(): List<Table<*>> = listOf(
    CAREER_SITES,
    CRAWL_LEASES,
    CRAWL_RUNS,
    JOB_POSTINGS,
    POSTING_SKILLS,
  )
}
