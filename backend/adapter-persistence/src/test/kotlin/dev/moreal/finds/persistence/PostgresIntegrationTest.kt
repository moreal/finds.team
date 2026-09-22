package dev.moreal.finds.persistence

import javax.sql.DataSource
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer

abstract class PostgresIntegrationTest {
  protected fun dataSource(): DataSource {
    val container = containerResult.getOrElse { error ->
      assumeTrue(false, "Docker unavailable: ${error.message}")
      error("unreachable")
    }
    return PGSimpleDataSource().apply {
      setURL(container.jdbcUrl)
      user = container.username
      password = container.password
    }
  }

  protected fun resetPublicSchema(dataSource: DataSource = dataSource()): DataSource {
    dataSource.connection.use { connection ->
      connection.createStatement().use { statement ->
        statement.execute("DROP SCHEMA IF EXISTS public CASCADE")
        statement.execute("CREATE SCHEMA public")
      }
    }
    return dataSource
  }

  private companion object {
    val containerResult: Result<PostgreSQLContainer> by lazy {
      runCatching {
        PostgreSQLContainer("postgres:17-alpine").apply { start() }
      }
    }
  }
}
