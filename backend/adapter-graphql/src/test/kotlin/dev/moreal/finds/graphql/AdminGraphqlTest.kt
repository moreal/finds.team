package dev.moreal.finds.graphql

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AdminGraphqlTest {
  @Test fun `anonymous crawl status access is forbidden`() = runTest {
    val result = GraphqlRuntime.create(emptyAccountFacade(), this).execute("{ crawlStatuses { __typename } }")
    assertEquals("FORBIDDEN", result.errors.singleOrNull()?.extensions?.get("code"))
  }
  @Test fun `anonymous audit access is forbidden`() = runTest {
    val result = GraphqlRuntime.create(emptyAccountFacade(), this).execute("{ auditEvents { __typename } }")
    assertEquals("FORBIDDEN", result.errors.singleOrNull()?.extensions?.get("code"))
  }
}
