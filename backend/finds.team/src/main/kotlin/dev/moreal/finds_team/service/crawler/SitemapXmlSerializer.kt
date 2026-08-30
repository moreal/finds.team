package dev.moreal.finds_team.service.crawler

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

internal data class SitemapUrlSet(
  val urls: List<SitemapUrl>,
) {
  init {
    require(urls.size <= 50000) { "Sitemap can contain up to 50,000 URLs" }
  }
}

internal data class SitemapUrl(
  val location: String,
)

internal object SitemapXmlSerializer {
  fun deserialize(xmlString: String): SitemapUrlSet {
    val urls = Jsoup.parse(xmlString, "", Parser.xmlParser())
      .select("url > loc")
      .map { element -> element.text().trim() }
      .filter { location -> location.length in 12..2048 }
      .distinct()
      .map(::SitemapUrl)

    return SitemapUrlSet(urls)
  }
}
