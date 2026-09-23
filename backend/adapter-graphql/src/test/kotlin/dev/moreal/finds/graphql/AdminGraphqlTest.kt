package dev.moreal.finds.graphql

import kotlinx.coroutines.test.runTest
import dev.moreal.finds.application.model.*
import dev.moreal.finds.application.port.*
import dev.moreal.finds.application.usecase.*
import dev.moreal.finds.application.security.Actor
import dev.moreal.finds.application.security.AuthenticationStrength
import dev.moreal.finds.domain.identity.*
import graphql.ExecutionInput
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class AdminGraphqlTest {
  @Test fun `site audit filter maps opaque site ID and rejects ambiguous or invalid targets`() = runTest {
    val searches = mutableListOf<AuditSearch>()
    val queries = Proxy.newProxyInstance(OperationsQueryPort::class.java.classLoader, arrayOf(OperationsQueryPort::class.java)) { _, method, args ->
      check(method.name == "auditPages")
      @Suppress("UNCHECKED_CAST") val keys = args!![0] as List<AuditPageKey>
      keys.map { searches.add(it.filter); Result.success(ConnectionPage<AuditRecord>(emptyList(), ConnectionPageInfo(false, false, null, null), 0)) }
    } as OperationsQueryPort
    val facade = FindsGraphqlFacade({ _, _ -> SearchPage(emptyList(), null, 0) }, { error("No registration") }, { error("No crawl") }, { emptyList() }, SecurityEventPort {}, operations = OperationsQueries(queries))
    val graph = GraphqlRuntime.create(facade, this)
    val principal = SessionPrincipal(Actor.User(UUID.randomUUID(), setOf(UserRole.ADMIN), Instant.now(), AuthenticationStrength.PASSKEY), UserSessionId(UUID.randomUUID()))
    val site = GlobalIdCodec.encode(NodeType.CareerSite, 42L)
    fun execute(filter: String) = graph.execute(ExecutionInput.newExecutionInput().query("{ auditEvents(filter: {$filter}) { totalCount error { code } } }")
      .graphQLContext { it.put(GraphqlRuntime.SESSION_PRINCIPAL, principal) }.build())
    val good = execute("atCareerSite: \"$site\"")
    assertEquals(emptyList(), good.errors)
    assertEquals(AuditSearch(targetType = "career_site", targetId = "42"), searches.single())
    for (filter in listOf("atCareerSite: \"$site\", targetType: \"career_site\"", "atCareerSite: \"$site\", targetId: \"42\"", "atCareerSite: \"bad\"", "atCareerSite: \"${GlobalIdCodec.encode(NodeType.CrawlRun, 42L)}\"")) {
      val result = execute(filter)
      assertEquals(emptyList(), result.errors)
      assertEquals("INVALID_FILTER", ((result.getData<Map<String, Any>>()!!["auditEvents"] as Map<*, *>)["error"] as Map<*, *>)["code"])
    }
    assertEquals(1, searches.size)
  }
  @Test fun `anonymous crawl status access is forbidden`() = runTest {
    val result = GraphqlRuntime.create(emptyAccountFacade(), this).execute("{ crawlStatuses { __typename } }")
    assertEquals("FORBIDDEN", result.errors.singleOrNull()?.extensions?.get("code"))
  }
  @Test fun `anonymous audit access is forbidden`() = runTest {
    val result = GraphqlRuntime.create(emptyAccountFacade(), this).execute("{ auditEvents { __typename } }")
    assertEquals("FORBIDDEN", result.errors.singleOrNull()?.extensions?.get("code"))
  }
}
