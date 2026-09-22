package dev.moreal.finds.application.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApplicationTypesTest {
  @Test
  fun `page request accepts one through one hundred`() {
    assertEquals(1, PageRequest(1).size)
    assertEquals(100, PageRequest(100).size)
    assertFailsWith<IllegalArgumentException> { PageRequest(0) }
    assertFailsWith<IllegalArgumentException> { PageRequest(-1) }
    assertFailsWith<IllegalArgumentException> { PageRequest(101) }
  }

  @Test
  fun `application identifiers require positive values`() {
    assertFailsWith<IllegalArgumentException> { CrawlRunId(0) }
    assertFailsWith<IllegalArgumentException> { CrawlRunId(-1) }
    assertEquals(1, CrawlRunId(1).value)
  }

  @Test
  fun `failure messages are sanitized and bounded`() {
    val failure = CrawlFailure(
      CrawlFailureCode.SOURCE_FETCH_FAILED,
      " token=secret\nboom ",
    )

    assertEquals("token=secret boom", failure.message)
    assertFailsWith<IllegalArgumentException> {
      CrawlFailure(CrawlFailureCode.SOURCE_FETCH_FAILED, "x".repeat(1_001))
    }
  }

  @Test
  fun `crawl change counts reject negative values`() {
    assertFailsWith<IllegalArgumentException> {
      CrawlChangeCounts(
        fetched = 1,
        inserted = -1,
        updated = 0,
        touched = 0,
        missing = 0,
        closed = 0,
        reopened = 0,
      )
    }
  }
}
