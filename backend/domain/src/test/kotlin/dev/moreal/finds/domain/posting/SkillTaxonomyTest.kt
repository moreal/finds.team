package dev.moreal.finds.domain.posting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SkillTaxonomyTest {
  @Test
  fun `English and Korean aliases resolve to canonical skills`() {
    val aliases = mapOf(
      "코틀린" to "kotlin", "자바" to "java", "스프링" to "spring",
      "자바스크립트" to "javascript", "타입스크립트" to "typescript", "리액트" to "react",
      "뷰" to "vue", "솔리드JS" to "solidjs", "노드js" to "nodejs", "파이썬" to "python",
      "장고" to "django", "패스트API" to "fastapi", "골랭" to "go", "러스트" to "rust",
      "C언어" to "c", "씨플러스플러스" to "cpp", "씨샵" to "csharp", "닷넷" to "dotnet",
      "스위프트" to "swift", "아이오에스" to "ios", "안드로이드" to "android",
      "플러터" to "flutter", "포스트그레스" to "postgresql", "마이에스큐엘" to "mysql",
      "레디스" to "redis", "몽고DB" to "mongodb", "카프카" to "kafka", "아마존 웹 서비스" to "aws",
      "구글 클라우드" to "gcp", "애저" to "azure", "도커" to "docker", "쿠버네티스" to "kubernetes",
      "테라폼" to "terraform", "리눅스" to "linux", "깃" to "git", "그래프큐엘" to "graphql",
      "레스트" to "rest", "스파크" to "spark", "에어플로우" to "airflow",
      "파이토치" to "pytorch", "텐서플로" to "tensorflow",
      "C++" to "cpp", "C#" to "csharp", ".NET" to "dotnet", "Node.js" to "nodejs",
      "K8s" to "kubernetes", "Postgres" to "postgresql", "Golang" to "go",
    )
    aliases.forEach { (alias, slug) ->
      assertEquals(slug, SkillTaxonomy.V1.resolve(" $alias ")?.slug, alias)
    }
    SkillTaxonomy.V1.skills.forEach { skill ->
      assertEquals(skill.slug, SkillTaxonomy.V1.resolve(skill.displayName)?.slug)
      assertEquals(skill.slug, SkillTaxonomy.V1.requireSkill(skill.slug).slug)
    }
    assertNull(SkillTaxonomy.V1.resolve("FutureDB"))
    assertFailsWith<UnknownSkillException> { SkillTaxonomy.V1.requireSkill("FutureDB") }
  }

  @Test
  fun `versioned catalog loads without I O and is immutable`() {
    val yaml = requireNotNull(javaClass.getResource("/skills.yaml")).readText()
    val loaded = SkillTaxonomy.fromYaml(yaml)
    assertEquals(SkillTaxonomy.V1.version, loaded.version)
    assertEquals(SkillTaxonomy.V1.skills, loaded.skills)
    assertFailsWith<UnsupportedOperationException> {
      (loaded.skills as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (loaded.skills.first().aliases as MutableList).clear()
    }
    assertFailsWith<IllegalArgumentException> { SkillTaxonomy.fromYaml("version: 999\nskills:\n") }
  }
}
