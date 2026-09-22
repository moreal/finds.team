package dev.moreal.finds.source.sitemap

import java.io.ByteArrayInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

data class SitemapLocation(
  val location: String,
  val lastModified: String?,
) {
  init {
    require(location.isNotBlank()) { "Sitemap location must not be blank" }
    require(lastModified == null || lastModified.isNotBlank()) {
      "Sitemap lastmod must not be blank"
    }
  }
}

sealed interface SitemapDocument {
  val locations: List<SitemapLocation>

  data class UrlSet(override val locations: List<SitemapLocation>) : SitemapDocument

  data class Index(override val locations: List<SitemapLocation>) : SitemapDocument
}

sealed interface SitemapParseResult {
  data class Success(val document: SitemapDocument) : SitemapParseResult

  data class Failure(val reason: String) : SitemapParseResult
}

object SitemapParser {
  fun parse(bytes: ByteArray): SitemapParseResult = try {
    val reader = xmlFactory().createXMLStreamReader(ByteArrayInputStream(bytes))
    reader.useReader {
      var root: String? = null
      var expectedEntry: String? = null
      var inEntry = false
      var location: String? = null
      var lastModified: String? = null
      val locations = mutableListOf<SitemapLocation>()

      while (reader.hasNext()) {
        when (reader.next()) {
          XMLStreamConstants.DTD -> error("DOCTYPE is not allowed in sitemap XML")
          XMLStreamConstants.START_ELEMENT -> {
            val name = reader.localName.lowercase()
            if (root == null) {
              root = name
              expectedEntry = when (name) {
                "urlset" -> "url"
                "sitemapindex" -> "sitemap"
                else -> error("Unsupported sitemap root: $name")
              }
            } else if (name == expectedEntry && !inEntry) {
              inEntry = true
              location = null
              lastModified = null
            } else if (inEntry && name == "loc") {
              location = reader.elementText.trim()
            } else if (inEntry && name == "lastmod") {
              lastModified = reader.elementText.trim().ifEmpty { null }
            }
          }
          XMLStreamConstants.END_ELEMENT -> {
            if (inEntry && reader.localName.equals(expectedEntry, ignoreCase = true)) {
              locations += SitemapLocation(
                location = requireNotNull(location) { "Sitemap entry is missing loc" },
                lastModified = lastModified,
              )
              inEntry = false
            }
          }
        }
      }
      val document = when (root) {
        "urlset" -> SitemapDocument.UrlSet(locations)
        "sitemapindex" -> SitemapDocument.Index(locations)
        else -> error("Sitemap document has no supported root")
      }
      SitemapParseResult.Success(document)
    }
  } catch (error: Exception) {
    SitemapParseResult.Failure(
      (error.message ?: "Malformed sitemap XML")
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
        .take(1_000),
    )
  }

  private fun xmlFactory(): XMLInputFactory = XMLInputFactory.newFactory().apply {
    setProperty(XMLInputFactory.SUPPORT_DTD, false)
    setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
    xmlResolver = javax.xml.stream.XMLResolver { _, _, _, _ ->
      throw IllegalArgumentException("External XML entities are not allowed")
    }
  }
}

private inline fun <R> XMLStreamReader.useReader(block: (XMLStreamReader) -> R): R =
  try {
    block(this)
  } finally {
    close()
  }
