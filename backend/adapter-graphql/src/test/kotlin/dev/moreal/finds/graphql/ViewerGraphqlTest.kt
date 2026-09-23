package dev.moreal.finds.graphql

import dev.moreal.finds.application.model.SearchPage
import dev.moreal.finds.application.port.SecurityEventPort
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ViewerGraphqlTest {
  @Test fun `anonymous viewer is null without errors`() = runTest {
    val graph = GraphqlRuntime.create(emptyAccountFacade(), this)
    val result = graph.execute("{ viewer { user { id roles } } }")
    assertEquals(emptyList(), result.errors)
    assertEquals(mapOf("viewer" to null), result.getData())
  }
}

internal fun emptyAccountFacade() = FindsGraphqlFacade(
  { _, _ -> SearchPage(emptyList(), null, 0) }, { error("No registration") }, { error("No crawl") },
  { emptyList() }, SecurityEventPort {},
)
