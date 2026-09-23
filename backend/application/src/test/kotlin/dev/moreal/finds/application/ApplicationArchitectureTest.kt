package dev.moreal.finds.application

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationArchitectureTest {
  @Test
  fun `security and transport frameworks are forbidden application imports`() {
    val forbiddenPrefixes = applicationForbiddenImportPrefixes()
    assertEquals(
      listOf("com.webauthn4j", "jakarta.servlet", "graphql"),
      listOf("com.webauthn4j.data", "jakarta.servlet.http", "graphql.schema")
        .filter { imported -> forbiddenPrefixes.any(imported::startsWith) }
        .map { imported -> imported.substringBeforeLast('.') },
    )
  }

  @Test
  fun `application source has no adapter or framework imports`() {
    val forbiddenPrefixes = applicationForbiddenImportPrefixes()
    val sourceRoot = Path.of("src/main/kotlin")
    val violations = Files.walk(sourceRoot).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.name.endsWith(".kt") }
        .flatMap { path ->
          Files.readAllLines(path).mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            val imported = trimmed.removePrefix("import ")
            if (
              trimmed.startsWith("import ") &&
              forbiddenPrefixes.any(imported::startsWith)
            ) {
              "${sourceRoot.relativize(path)}:${index + 1}: $trimmed"
            } else {
              null
            }
          }.stream()
        }
        .sorted()
        .toList()
    }

    assertEquals(emptyList(), violations)
  }

  private fun applicationForbiddenImportPrefixes() = listOf(
    "org.springframework",
    "org.jooq",
    "io.ktor",
    "java.sql",
    "jakarta.persistence",
    "jakarta.servlet",
    "com.webauthn4j",
    "graphql",
    "org.jsoup",
  )
}
