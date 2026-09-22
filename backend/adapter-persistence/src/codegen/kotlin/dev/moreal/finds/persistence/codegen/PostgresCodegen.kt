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
  require(args.size == 2) { "Expected migration directory and generated-source directory" }
  val migrations = File(args[0]).canonicalFile
  val output = File(args[1]).canonicalFile
  require(migrations.isDirectory) { "Migration directory does not exist: $migrations" }

  PostgreSQLContainer("postgres:17-alpine").use { postgres ->
    postgres.start()
    Flyway.configure()
      .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
      .locations("filesystem:${migrations.path}")
      .load()
      .migrate()

    GenerationTool.generate(
      Configuration()
        .withJdbc(
          Jdbc()
            .withDriver("org.postgresql.Driver")
            .withUrl(postgres.jdbcUrl)
            .withUser(postgres.username)
            .withPassword(postgres.password),
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
}
