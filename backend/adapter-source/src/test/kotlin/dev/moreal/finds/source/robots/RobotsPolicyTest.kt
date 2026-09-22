package dev.moreal.finds.source.robots

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RobotsPolicyTest {
  @Test
  fun `selects exact product group combines equal groups and otherwise uses wildcard`() {
    val policy = RobotsPolicy.parse(
      """
      User-agent: *
      Disallow: /

      User-Agent: finds
      Disallow: /private
      User-agent: finds
      Allow: /private/public

      User-agent: finds_team
      Disallow: /admin
      """.trimIndent(),
    )

    assertTrue(policy.allows("finds_team", "/jobs"))
    assertFalse(policy.allows("finds_team", "/admin/panel"))
    assertTrue(policy.allows("finds", "/private/public/1"))
    assertFalse(policy.allows("finds", "/private/1"))
    assertFalse(policy.allows("other", "/jobs"))
  }

  @Test
  fun `longest rule wins and allow wins equal specificity`() {
    val policy = RobotsPolicy.parse(
      """
      user-agent: *
      disallow: /jobs/*/apply$
      allow: /jobs/public/apply$
      disallow: /same
      allow: /same
      """.trimIndent(),
    )

    assertFalse(policy.allows("finds_team", "/jobs/123/apply"))
    assertTrue(policy.allows("finds_team", "/jobs/123/apply?from=home"))
    assertTrue(policy.allows("finds_team", "/jobs/public/apply"))
    assertTrue(policy.allows("finds_team", "/same"))
    assertTrue(policy.allows("finds_team", "/robots.txt"))
  }

  @Test
  fun `comments blank disallow and percent encoded unreserved octets are handled`() {
    val policy = RobotsPolicy.parse(
      """
      user-agent: * # everyone
      disallow:
      disallow: /caf%C3%A9
      disallow: /~private
      """.trimIndent(),
    )

    assertFalse(policy.allows("finds_team", "/caf%C3%A9"))
    assertFalse(policy.allows("finds_team", "/%7Eprivate"))
    assertTrue(policy.allows("finds_team", "/public"))
  }

  @Test
  fun `collects sitemap records independently of groups`() {
    val policy = RobotsPolicy.parse(
      """
      Sitemap: https://jobs.example/sitemap.xml
      user-agent: *
      allow: /
      sitemap: https://jobs.example/jobs.xml # current
      """.trimIndent(),
    )

    assertEquals(
      listOf("https://jobs.example/sitemap.xml", "https://jobs.example/jobs.xml"),
      policy.sitemaps,
    )
  }
}
