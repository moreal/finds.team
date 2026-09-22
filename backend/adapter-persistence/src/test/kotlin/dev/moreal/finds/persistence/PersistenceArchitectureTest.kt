package dev.moreal.finds.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PersistenceArchitectureTest {
  @Test
  fun `persistence source has no Spring JPA H2 bootstrap or legacy imports`() {
    val forbidden = listOf(
      "org.springframework",
      "jakarta.persistence",
      "org.hibernate",
      "com.h2database",
      "dev.moreal.finds_team",
    )
    assertEquals(emptyList(), importViolations(listOf("src/main/kotlin", "src/codegen/kotlin"), forbidden))
  }

  @Test
  fun `runtime repositories use jOOQ rather than raw JDBC`() {
    assertEquals(
      emptyList(),
      importViolations(listOf("src/main/kotlin"), listOf("java.sql", "javax.sql")),
    )
  }

  @Test
  fun `code generator disables JPA annotations and targets build directory`() {
    val generator = Files.readString(
      Path.of("src/codegen/kotlin/dev/moreal/finds/persistence/codegen/PostgresCodegen.kt"),
    )
    assertFalse(generator.contains("withJpaAnnotations(true)"))
    assertFalse(generator.contains("src/main/generated"))
  }

  private fun importViolations(roots: List<String>, prefixes: List<String>): List<String> =
    roots.flatMap { rootValue ->
      val root = Path.of(rootValue)
      Files.walk(root).use { paths ->
        paths.filter { it.isRegularFile() && it.name.endsWith(".kt") }
          .flatMap { path ->
            Files.readAllLines(path).mapIndexedNotNull { index, line ->
              val trimmed = line.trim()
              val imported = trimmed.removePrefix("import ")
              if (trimmed.startsWith("import ") && prefixes.any(imported::startsWith)) {
                "$rootValue/${root.relativize(path)}:${index + 1}: $trimmed"
              } else null
            }.stream()
          }
          .toList()
      }
    }.sorted()
}
