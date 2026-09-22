package dev.moreal.finds_team.config

import org.junit.jupiter.api.assertThrows
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class FindsPropertiesTest {
  @Test
  fun `defaults provide an identified and bounded crawler`() {
    val properties = FindsProperties()

    assertEquals("finds.team", properties.source.userAgentProduct)
    assertEquals(Duration.ofMillis(500), properties.source.minimumHostSpacing)
    assertEquals(5L * 1024 * 1024, properties.source.maximumResponseBytes)
    assertEquals(3, properties.crawl.globalConcurrency)
    assertEquals(2, properties.crawl.closeAfterMisses)
  }

  @Test
  fun `source settings reject an invalid contact URL`() {
    assertThrows<IllegalArgumentException> {
      FindsProperties.Source(contactUrl = "mailto:operator@example.com")
    }
  }

  @Test
  fun `crawl settings reject non-positive intervals`() {
    assertThrows<IllegalArgumentException> {
      FindsProperties.Crawl(scanInterval = Duration.ZERO)
    }
  }
}
