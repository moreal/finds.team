package dev.moreal.finds_team.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeConfigurationTest {
  private val configuration = RuntimeConfiguration()

  @Test
  fun `source properties map to the bounded protocol settings`() {
    val properties = FindsProperties(
      source = FindsProperties.Source(
        maximumResponseBytes = 123_456,
        maximumRedirects = 2,
        sitemapMaximumDepth = 3,
        sitemapMaximumDocuments = 12,
        sitemapMaximumUrls = 345,
        sitemapMaximumTotalBytes = 2_000_000,
        minimumHostSpacing = Duration.ofSeconds(2),
      ),
    )

    val settings = configuration.sourceProtocolSettings(properties)

    assertEquals(123_456, settings.maxResponseBytes)
    assertEquals(2, settings.maxRedirects)
    assertEquals(3, settings.maxSitemapDepth)
    assertEquals(12, settings.maxSitemapDocuments)
    assertEquals(345, settings.maxSitemapUrls)
    assertEquals(2_000_000, settings.maxSitemapTotalBytes)
    assertEquals(Duration.ofSeconds(2), settings.minimumHostSpacing)
  }
}
