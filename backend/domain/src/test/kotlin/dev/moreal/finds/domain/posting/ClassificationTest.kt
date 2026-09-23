package dev.moreal.finds.domain.posting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClassificationTest {
  @Test
  fun `Korean and English sections and inline hints classify requirement levels`() {
    val fixtures = listOf(
      "자격요건\n코틀린, Java\n우대사항\n파이썬\n담당업무\nReact" to
        mapOf("kotlin" to SkillRequirementLevel.REQUIRED, "java" to SkillRequirementLevel.REQUIRED,
          "python" to SkillRequirementLevel.PREFERRED, "react" to SkillRequirementLevel.MENTIONED),
      "Requirements:\nKotlin, Java\nNice to have:\nPython\nResponsibilities:\nReact" to
        mapOf("kotlin" to SkillRequirementLevel.REQUIRED, "java" to SkillRequirementLevel.REQUIRED,
          "python" to SkillRequirementLevel.PREFERRED, "react" to SkillRequirementLevel.MENTIONED),
      "Java required\nKotlin preferred\nPython 필수\nReact 우대\nRedis" to
        mapOf("java" to SkillRequirementLevel.REQUIRED, "kotlin" to SkillRequirementLevel.PREFERRED,
          "python" to SkillRequirementLevel.REQUIRED, "react" to SkillRequirementLevel.PREFERRED,
          "redis" to SkillRequirementLevel.MENTIONED),
      "Requirements: Java\nPreferred: Kotlin\nTech stack: Redis" to
        mapOf("java" to SkillRequirementLevel.REQUIRED, "kotlin" to SkillRequirementLevel.PREFERRED,
          "redis" to SkillRequirementLevel.MENTIONED),
    )
    fixtures.forEach { (text, expected) ->
      assertEquals(expected, classifyPosting(raw(description = text)).skills
        .filter { it.slug != null }.associate { requireNotNull(it.slug) to it.level }, text)
    }
  }

  @Test
  fun `aliases collapse to strongest evidence and unknown skill candidates retain original text`() {
    val result = classifyPosting(raw(description = "Skills:\nFutureDB, 코틀린\nPreferred:\nKotlin\nRequired:\nKOTLIN"))
    assertEquals(1, result.skills.count { it.slug == "kotlin" })
    assertEquals(SkillRequirementLevel.REQUIRED, result.skills.single { it.slug == "kotlin" }.level)
    assertTrue(result.skills.any { it.slug == null && it.text == "FutureDB" })
  }

  @Test
  fun `joined skill lists preserve unknown entries and negation does not imply a requirement`() {
    val result = classifyPosting(raw(description = "Skills:\nKotlin and FutureDB / OtherDB\nRequirements:\nJava is not required"))
    assertEquals(setOf("FutureDB", "OtherDB"), result.skills.filter { it.slug == null }.map { it.text }.toSet())
    assertEquals(SkillRequirementLevel.MENTIONED, result.skills.single { it.slug == "java" }.level)
  }

  @Test
  fun `token boundaries avoid language collisions and natural language go`() {
    val cases = mapOf(
      "C++ C# .NET" to setOf("cpp", "csharp", "dotnet"),
      "C/C++/C#" to setOf("c", "cpp", "csharp"),
      "JavaScript GraphQL Django cargo GitHub Springfield reactively" to setOf("javascript", "graphql", "django"),
      "We go to market and let customers react to changes" to emptySet(),
      "Go developer, Golang and React developer" to setOf("go", "react"),
      "Go-to-market and ongoing work" to emptySet(),
      "Go to market with us. Go ahead and apply." to emptySet(),
      "코틀린과 자바를 사용합니다" to setOf("kotlin", "java"),
      "Kotlin과 Java를 사용합니다. C++. Node.js. .NET." to setOf("kotlin", "java", "cpp", "nodejs", "dotnet"),
    )
    cases.forEach { (text, expected) ->
      assertEquals(expected, classifyPosting(raw(description = text)).skills.mapNotNull { it.slug }.toSet(), text)
    }
  }

  @Test
  fun `normalization accepts provider fixtures and preserves unknown hints`() {
    listOf("FULL_TIME", "full_time", "정규직", "full-time").forEach {
      assertEquals(EmploymentType.FULL_TIME, classifyPosting(raw(employment = it)).employment.value)
    }
    listOf("CONTRACT", "계약직").forEach {
      assertEquals(EmploymentType.CONTRACT, classifyPosting(raw(employment = it)).employment.value)
    }
    listOf("internship" to EmploymentType.INTERNSHIP, "파트타임" to EmploymentType.PART_TIME).forEach { (hint, value) ->
      assertEquals(value, classifyPosting(raw(employment = hint)).employment.value)
    }
    listOf("remote" to RemotePolicy.REMOTE, "재택근무" to RemotePolicy.REMOTE,
      "hybrid" to RemotePolicy.HYBRID, "출근" to RemotePolicy.ONSITE).forEach { (hint, value) ->
      assertEquals(value, classifyPosting(raw(remote = hint)).remote.value)
    }
    val unknown = classifyPosting(raw(employment = "Seasonal special", remote = "Negotiable", location = "  Moon   Base  "))
    assertEquals(EmploymentType.UNKNOWN, unknown.employment.value)
    assertEquals("Seasonal special", unknown.employment.rawValue)
    assertEquals(RemotePolicy.UNKNOWN, unknown.remote.value)
    assertEquals("Negotiable", unknown.remote.rawValue)
    assertEquals(NormalizedLocation("Moon Base", "moon base"), unknown.location)
    assertEquals("seoul", classifyPosting(raw(location = "서울특별시")).location?.searchValue)
    assertEquals("seoul", classifyPosting(raw(location = "Seoul")).location?.searchValue)
    assertEquals(null, classifyPosting(raw()).location)
  }

  @Test
  fun `roles come from title and absent or contradictory hints stay unknown`() {
    mapOf("백엔드 개발자" to RoleCategory.BACKEND, "Frontend Engineer" to RoleCategory.FRONTEND,
      "Full-stack Developer" to RoleCategory.FULLSTACK, "iOS Engineer" to RoleCategory.MOBILE,
      "Data Engineer" to RoleCategory.DATA, "DevOps Engineer" to RoleCategory.DEVOPS,
      "Product Designer" to RoleCategory.DESIGN, "Product Manager" to RoleCategory.PRODUCT,
      "QA Engineer" to RoleCategory.QA, "Mystery specialist" to RoleCategory.UNKNOWN).forEach { (title, expected) ->
      assertEquals(expected, classifyPosting(raw(title = title)).role.value, title)
    }
    val result = classifyPosting(raw(title = "Mystery specialist", description = "Full-time remote backend opportunities"))
    assertEquals(RoleCategory.UNKNOWN, result.role.value)
    assertEquals("Mystery specialist", result.role.rawValue)
    assertEquals(EmploymentType.UNKNOWN, result.employment.value)
    assertEquals(RemotePolicy.UNKNOWN, result.remote.value)
    assertEquals(RemotePolicy.UNKNOWN, classifyPosting(raw(remote = "remote or onsite")).remote.value)
  }

  private fun raw(title: String = "Engineer", description: String = "", employment: String? = null,
    remote: String? = null, location: String? = null) = RawPosting(
    "one", title, description,
    (PostingUrl.parse("https://jobs.example/one") as PostingUrlResult.Valid).url,
    employmentHint = employment, remoteHint = remote, locationHint = location,
  )
}
