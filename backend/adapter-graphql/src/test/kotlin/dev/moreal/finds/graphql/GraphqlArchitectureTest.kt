package dev.moreal.finds.graphql

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphqlArchitectureTest {
  @Test fun `GraphQL adapter has no framework persistence source or legacy imports`() {
    val forbidden = listOf(
      "org.springframework", "org.jooq", "java.sql", "javax.sql", "jakarta.persistence",
      "com.h2database", "dev.moreal.finds.source", "dev.moreal.finds.persistence",
      "dev.moreal.finds_team",
    )
    val root = Path.of("src/main/kotlin")
    val violations = Files.walk(root).use { paths ->
      paths.filter { it.isRegularFile() && it.name.endsWith(".kt") }
        .flatMap { path ->
          Files.readAllLines(path).mapIndexedNotNull { index, line ->
            val imported = line.trim().removePrefix("import ")
            if (line.trim().startsWith("import ") && forbidden.any(imported::startsWith)) {
              "${root.relativize(path)}:${index + 1}: ${line.trim()}"
            } else null
          }.stream()
        }.sorted().toList()
    }
    assertEquals(emptyList(), violations)
  }
}
