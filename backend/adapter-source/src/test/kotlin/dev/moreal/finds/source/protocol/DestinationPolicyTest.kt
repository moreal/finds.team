package dev.moreal.finds.source.protocol

import dev.moreal.finds.domain.career.SiteHost
import dev.moreal.finds.domain.career.SiteUrl
import dev.moreal.finds.domain.career.SiteUrlResult
import java.net.InetAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DestinationPolicyTest {
  @Test
  fun `allows a public destination only when every address is public`() = runTest {
    val resolver = FakeResolver(
      listOf(address("93.184.216.34"), address("2606:2800:220:1:248:1893:25c8:1946")),
    )
    val policy = DestinationPolicy(resolver)
    val target = url("https://jobs.example/openings")

    assertEquals(DestinationDecision.Allowed, policy.evaluate(target, setOf(target.host)))
    assertEquals(listOf(target.host), resolver.hosts)
  }

  @Test
  fun `rejects an unapproved host before DNS`() = runTest {
    val resolver = FakeResolver(listOf(address("93.184.216.34")))
    val target = url("https://jobs.example")

    val decision = assertIs<DestinationDecision.Rejected>(
      DestinationPolicy(resolver).evaluate(target, setOf(SiteHost("api.example"))),
    )

    assertEquals(WebFailureCode.INVALID_DESTINATION, decision.failure.code)
    assertEquals(emptyList(), resolver.hosts)
  }

  @Test
  fun `rejects destination when any resolved address is nonpublic`() = runTest {
    val blocked = listOf(
      "0.0.0.0", "127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.1.1",
      "172.16.0.1", "192.168.0.1", "224.0.0.1", "::", "::1", "fe80::1", "fc00::1",
    )

    blocked.forEach { value ->
      val target = url("https://jobs.example")
      val resolver = FakeResolver(listOf(address("93.184.216.34"), address(value)))
      val decision = assertIs<DestinationDecision.Rejected>(
        DestinationPolicy(resolver).evaluate(target, setOf(target.host)),
      )
      assertEquals(WebFailureCode.DNS_REJECTED, decision.failure.code, value)
    }
  }

  @Test
  fun `empty and failed DNS are typed rejections`() = runTest {
    val target = url("https://jobs.example")
    val empty = assertIs<DestinationDecision.Rejected>(
      DestinationPolicy(FakeResolver(emptyList())).evaluate(target, setOf(target.host)),
    )
    val failed = assertIs<DestinationDecision.Rejected>(
      DestinationPolicy(FakeResolver(failure = IllegalStateException("dns\nsecret")))
        .evaluate(target, setOf(target.host)),
    )

    assertEquals(WebFailureCode.DNS_REJECTED, empty.failure.code)
    assertEquals(WebFailureCode.DNS_REJECTED, failed.failure.code)
    assertEquals("DNS resolution failed for jobs.example: dns secret", failed.failure.message)
  }

  private class FakeResolver(
    private val addresses: List<InetAddress> = emptyList(),
    private val failure: RuntimeException? = null,
  ) : HostResolver {
    val hosts = mutableListOf<SiteHost>()

    override suspend fun resolve(host: SiteHost): List<InetAddress> {
      hosts += host
      failure?.let { throw it }
      return addresses
    }
  }

  private fun address(value: String): InetAddress = InetAddress.getByName(value)

  private fun url(value: String): SiteUrl =
    assertIs<SiteUrlResult.Valid>(SiteUrl.parse(value)).url
}
