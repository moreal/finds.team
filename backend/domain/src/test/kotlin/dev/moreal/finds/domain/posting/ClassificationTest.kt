package dev.moreal.finds.domain.posting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable

class ClassificationTest {
  @Test
  fun `shared list suffixes apply to every member while independent qualifiers retain scope`() {
    val required = SkillRequirementLevel.REQUIRED
    val preferred = SkillRequirementLevel.PREFERRED
    val mentioned = SkillRequirementLevel.MENTIONED
    val fixtures = listOf(
      "Requirements:\nJava and Kotlin not required" to mapOf("java" to mentioned, "kotlin" to mentioned),
      "Java, Kotlin required" to mapOf("java" to required, "kotlin" to required),
      "자격요건\n자바 및 코틀린 필수는 아닙니다" to mapOf("java" to mentioned, "kotlin" to mentioned),
      "자바, 코틀린 필수" to mapOf("java" to required, "kotlin" to required),
      "Java and Kotlin preferred" to mapOf("java" to preferred, "kotlin" to preferred),
      "Java required and Kotlin preferred" to mapOf("java" to required, "kotlin" to preferred),
      "Java required, Kotlin not required" to mapOf("java" to required, "kotlin" to mentioned),
      "자바 필수 및 코틀린 우대" to mapOf("java" to required, "kotlin" to preferred),
      "Java, Kotlin required; Python, Rust preferred" to mapOf("java" to required, "kotlin" to required,
        "python" to preferred, "rust" to preferred),
      "Requirements:\nNo Java and Kotlin experience required" to mapOf("java" to mentioned, "kotlin" to mentioned),
      "Java; Kotlin required" to mapOf("java" to mentioned, "kotlin" to required),
      "Java not required. Kotlin required" to mapOf("java" to mentioned, "kotlin" to required),
      "Java not required but Kotlin required" to mapOf("java" to mentioned, "kotlin" to required),
    )
    assertClassificationFixtures(fixtures)
  }

  @Test
  fun `qualification headings do not turn everyday Go and React prose into skills`() {
    val required = SkillRequirementLevel.REQUIRED
    val fixtures = listOf(
      "Requirements:\nYou go above and beyond for customers" to emptyMap(),
      "Requirements:\nReact to incidents quickly" to emptyMap(),
      "자격요건\nYou go above and beyond for customers\nReact to incidents quickly" to emptyMap(),
      "Skills:\nReact to incidents quickly and go above and beyond" to emptyMap(),
      "Requirements:\nGo, React" to mapOf("go" to required, "react" to required),
      "Requirements:\ngo and react experience required" to mapOf("go" to required, "react" to required),
      "자격요건\nGo 및 React 개발 경험 필수" to mapOf("go" to required, "react" to required),
      "Requirements:\nExperience with Go and React" to mapOf("go" to required, "react" to required),
      "Requirements:\nReact developer and Go engineer" to mapOf("react" to required, "go" to required),
    )
    assertClassificationFixtures(fixtures)
  }

  @Test
  fun `English and Korean explicit requirement negations precede positive hints`() {
    val mentioned = SkillRequirementLevel.MENTIONED
    val required = SkillRequirementLevel.REQUIRED
    assertClassificationFixtures(listOf(
      "Requirements:\nNo Java experience required" to mapOf("java" to mentioned),
      "Requirements:\nKotlin 필수는 아닙니다" to mapOf("kotlin" to mentioned),
      "Requirements:\nJava experience is not required" to mapOf("java" to mentioned),
      "자격요건\n코틀린 필수가 아닙니다" to mapOf("kotlin" to mentioned),
      "자격요건\n코틀린 필수 아님" to mapOf("kotlin" to mentioned),
      "No Java experience required; Kotlin required" to mapOf("java" to mentioned, "kotlin" to required),
      "Java experience required" to mapOf("java" to required),
      "코틀린 필수입니다" to mapOf("kotlin" to required),
    ))
  }

  private fun assertClassificationFixtures(fixtures: List<Pair<String, Map<String, SkillRequirementLevel>>>) {
    assertAll(fixtures.map { (text, expected) ->
      Executable {
        assertEquals(expected, classifyPosting(raw(description = text)).skills
          .filter { it.slug != null }.associate { requireNotNull(it.slug) to it.level }, text)
      }
    })
  }

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
