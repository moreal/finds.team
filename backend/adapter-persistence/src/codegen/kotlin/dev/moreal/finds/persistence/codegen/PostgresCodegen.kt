package dev.moreal.finds.persistence.codegen

import java.io.File
import org.flywaydb.core.Flyway
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.testcontainers.postgresql.PostgreSQLContainer

fun main(args: Array<String>) {
  require(args.size == 2 || args.size == 5) {
    "Expected migration and generated-source directories, optionally followed by JDBC URL, user, and password"
  }
  val migrations = File(args[0]).canonicalFile
  val output = File(args[1]).canonicalFile
  require(migrations.isDirectory) { "Migration directory does not exist: $migrations" }

  if (args.size == 5) {
    generate(migrations, output, args[2], args[3], args[4])
    return
  }

  PostgreSQLContainer("postgres:17-alpine").use { postgres ->
    postgres.start()
    generate(migrations, output, postgres.jdbcUrl, postgres.username, postgres.password)
  }
}

private fun generate(
  migrations: File,
  output: File,
  jdbcUrl: String,
  user: String,
  password: String,
) {
  Flyway.configure()
    .dataSource(jdbcUrl, user, password)
    .locations("filesystem:${migrations.path}")
    .load()
    .migrate()

  GenerationTool.generate(
    Configuration()
      .withJdbc(
        Jdbc()
          .withDriver("org.postgresql.Driver")
          .withUrl(jdbcUrl)
          .withUser(user)
          .withPassword(password),
      )
      .withGenerator(
        Generator()
          .withName("org.jooq.codegen.KotlinGenerator")
          .withDatabase(
            Database()
              .withName("org.jooq.meta.postgres.PostgresDatabase")
              .withInputSchema("public")
              .withIncludes(".*")
              .withExcludes("flyway_schema_history"),
          )
          .withGenerate(
            Generate()
              .withDaos(false)
              .withPojos(false)
              .withJpaAnnotations(false)
              .withValidationAnnotations(false)
              .withDeprecated(false),
          )
          .withTarget(
            Target()
              .withPackageName("dev.moreal.finds.persistence.jooq.generated")
              .withDirectory(output.path)
              .withEncoding("UTF-8")
              .withClean(true),
          ),
      ),
  )
}
