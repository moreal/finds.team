package dev.moreal.finds.domain

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchitectureTest {
  @Test
  fun `domain source has no forbidden imports`() {
    val forbiddenPrefixes = listOf(
      "org.springframework",
      "org.jooq",
      "io.ktor",
      "java.sql",
      "jakarta.persistence",
      "java.nio.file",
      "kotlin.io.path",
    )
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
}
