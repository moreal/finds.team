package dev.moreal.finds_team.security

import kotlin.test.*
import org.springframework.mock.web.MockHttpServletRequest

class TrustedClientAddressTest {
  @Test fun `untrusted forwarding ignored and trusted chain is traversed only from the right`() {
    val request = MockHttpServletRequest().apply {
      remoteAddr = "10.0.0.5"; addHeader("X-Forwarded-For", "1.1.1.1, 203.0.113.2, 10.0.0.6")
    }
    assertEquals("10.0.0.5", TrustedClientAddress(emptySet()).resolve(request))
    assertEquals("203.0.113.2", TrustedClientAddress(setOf("10.0.0.0/24")).resolve(request))
    request.remoteAddr = "192.0.2.1"
    assertEquals("192.0.2.1", TrustedClientAddress(setOf("10.0.0.0/24")).resolve(request))
    request.remoteAddr = "10.0.0.5"; request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For", "attacker.example")
    assertEquals("10.0.0.5", TrustedClientAddress(setOf("10.0.0.0/24")).resolve(request))
  }
}
