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
    assertEquals(
      listOf("import com.webauthn4j.data", "import jakarta.servlet.http", "import graphql.schema"),
      listOf("import com.webauthn4j.data", "import jakarta.servlet.http", "import graphql.schema")
        .filter(::isForbiddenImportLine),
    )
  }

  @Test
  fun `JDBC datasource import is forbidden while adjacent packages remain allowed`() {
    assertEquals(
      listOf("import javax.sql.DataSource", "import javax.sql"),
      listOf(
        "import javax.sql.DataSource",
        "import javax.sql",
        "import javax.sqlx.DataSource",
        "import java.sqlx.Connection",
        "import graphqlish.Schema",
        "import com.webauthn4jx.Parser",
        "import jakarta.servletx.Servlet",
      ).filter(::isForbiddenImportLine),
    )
  }

  @Test
  fun `application source has no adapter or framework imports`() {
    val sourceRoot = Path.of("src/main/kotlin")
    val violations = Files.walk(sourceRoot).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.name.endsWith(".kt") }
        .flatMap { path ->
          Files.readAllLines(path).mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (isForbiddenImportLine(trimmed)) {
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

  private fun isForbiddenImportLine(line: String): Boolean {
    if (!line.startsWith("import ")) return false
    val imported = line.removePrefix("import ")
    return applicationForbiddenImportPrefixes().any { prefix ->
      imported == prefix || imported.startsWith("$prefix.")
    }
  }

  private fun applicationForbiddenImportPrefixes() = listOf(
    "org.springframework",
    "org.jooq",
    "io.ktor",
    "java.sql",
    "javax.sql",
    "jakarta.persistence",
    "jakarta.servlet",
    "com.webauthn4j",
    "graphql",
    "org.jsoup",
  )
}
