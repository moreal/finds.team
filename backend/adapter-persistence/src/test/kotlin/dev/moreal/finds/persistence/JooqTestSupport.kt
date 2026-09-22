package dev.moreal.finds.persistence

import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL

fun PostgresIntegrationTest.migratedContext(): Pair<DataSource, DSLContext> {
  val dataSource = resetPublicSchema()
  Flyway.configure().dataSource(dataSource).load().migrate()
  return dataSource to DSL.using(dataSource, SQLDialect.POSTGRES)
}
