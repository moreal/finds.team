package dev.moreal.finds.source

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals

class SourceArchitectureTest {
  @Test
  fun `source main has no framework persistence or legacy imports`() {
    assertEquals(
      emptyList(),
      importViolations(
        listOf(
          "org.springframework", "org.jooq", "java.sql", "jakarta.persistence",
          "dev.moreal.finds_team",
        ),
      ),
    )
  }

  @Test
  fun `provider adapters cannot own crawl cadence retry or reconciliation policy`() {
    val forbidden = listOf(
      "dev.moreal.finds.domain.crawl.ClosePolicy",
      "dev.moreal.finds.domain.crawl.CrawlEligibility",
      "dev.moreal.finds.domain.crawl.RetryPolicy",
      "dev.moreal.finds.domain.crawl.ReconciliationResult",
      "dev.moreal.finds.domain.crawl.reconcile",
    )
    assertEquals(emptyList(), importViolations(forbidden, "src/main/kotlin/dev/moreal/finds/source/provider"))
  }

  @Test
  fun `normal tests never construct the live CIO transport`() {
    val root = Path.of("src/test/kotlin")
    val violations = Files.walk(root).use { paths ->
      paths.filter { it.isRegularFile() && it.name.endsWith(".kt") }
        .filter { it.name != "SourceArchitectureTest.kt" }
        .filter { Files.readString(it).contains("KtorSingleRequestTransport.create") }
        .map(root::relativize)
        .map(Path::toString)
        .sorted()
        .toList()
    }
    assertEquals(emptyList(), violations)
  }

  private fun importViolations(
    prefixes: List<String>,
    rootValue: String = "src/main/kotlin",
  ): List<String> {
    val root = Path.of(rootValue)
    return Files.walk(root).use { paths ->
      paths.filter { it.isRegularFile() && it.name.endsWith(".kt") }
        .flatMap { path ->
          Files.readAllLines(path).mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            val imported = trimmed.removePrefix("import ")
            if (trimmed.startsWith("import ") && prefixes.any(imported::startsWith)) {
              "${root.relativize(path)}:${index + 1}: $trimmed"
            } else null
          }.stream()
        }
        .sorted()
        .toList()
    }
  }
}
