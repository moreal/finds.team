package dev.moreal.finds.domain.posting

import java.util.Collections
import java.util.Locale

class SkillDefinition internal constructor(val slug: String, val displayName: String, aliases: List<String>) {
  val aliases: List<String> = Collections.unmodifiableList(aliases.toList())
  override fun equals(other: Any?): Boolean = other is SkillDefinition &&
    slug == other.slug && displayName == other.displayName && aliases == other.aliases
  override fun hashCode(): Int = 31 * (31 * slug.hashCode() + displayName.hashCode()) + aliases.hashCode()
  override fun toString(): String = "SkillDefinition($slug)"
}

class UnknownSkillException(val slug: String) : IllegalArgumentException("Unknown canonical skill")

/** An immutable snapshot. Loading accepts catalog text; classification never opens resources. */
class SkillTaxonomy private constructor(val version: Int, skills: List<SkillDefinition>) {
  val skills: List<SkillDefinition> = Collections.unmodifiableList(skills.toList())
  private val bySlug = skills.associateBy { it.slug }
  private val byAlias = buildMap {
    skills.forEach { skill ->
      (skill.aliases + skill.slug).forEach { alias ->
        val key = alias.trim().lowercase(Locale.ROOT)
        require(get(key) == null || get(key) == skill) { "Ambiguous skill alias" }
        put(key, skill)
      }
    }
  }

  fun resolve(alias: String): SkillDefinition? = byAlias[alias.trim().lowercase(Locale.ROOT)]
  fun requireSkill(slug: String): SkillDefinition = bySlug[slug] ?: throw UnknownSkillException(slug)

  companion object {
    /** Compiled snapshot of skills.yaml, checked for parity by the domain suite. */
    val V1: SkillTaxonomy = fromYaml(V1_CATALOG)

    /** Deliberately accepts only our versioned YAML subset: slug -> quoted string array. */
    fun fromYaml(text: String): SkillTaxonomy {
      val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
      require(lines.size >= 3 && lines[0] == "version: 1" && lines[1] == "skills:") {
        "Unsupported or malformed taxonomy version"
      }
      val row = Regex("""  ([a-z][a-z0-9-]*): \[("[^"\\]+"(?:, "[^"\\]+")*)]""")
      val skills = lines.drop(2).map { line ->
        val match = requireNotNull(row.matchEntire(line)) { "Malformed taxonomy entry" }
        val aliases = match.groupValues[2].split(", ").map { it.removeSurrounding("\"") }
        SkillDefinition(match.groupValues[1], aliases.first(), aliases)
      }
      require(skills.map { it.slug }.distinct().size == skills.size) { "Duplicate skill slug" }
      return SkillTaxonomy(1, skills)
    }
  }
}

private const val V1_CATALOG = """
version: 1
skills:
  kotlin: ["Kotlin", "코틀린"]
  java: ["Java", "자바"]
  spring: ["Spring", "Spring Framework", "스프링", "스프링 프레임워크"]
  javascript: ["JavaScript", "JS", "자바스크립트"]
  typescript: ["TypeScript", "TS", "타입스크립트"]
  react: ["React", "React.js", "ReactJS", "리액트"]
  vue: ["Vue", "Vue.js", "VueJS", "뷰"]
  solidjs: ["SolidJS", "Solid.js", "솔리드JS"]
  nodejs: ["Node.js", "NodeJS", "노드js", "노드"]
  python: ["Python", "파이썬"]
  django: ["Django", "장고"]
  fastapi: ["FastAPI", "Fast API", "패스트API"]
  go: ["Go", "Golang", "골랭", "고언어"]
  rust: ["Rust", "러스트"]
  c: ["C", "C언어", "씨언어"]
  cpp: ["C++", "CPP", "씨플러스플러스"]
  csharp: ["C#", "CSharp", "씨샵", "씨샤프"]
  dotnet: [".NET", "dotnet", "닷넷"]
  swift: ["Swift", "스위프트"]
  ios: ["iOS", "아이오에스"]
  android: ["Android", "안드로이드"]
  flutter: ["Flutter", "플러터"]
  postgresql: ["PostgreSQL", "Postgres", "포스트그레스", "포스트그레SQL"]
  mysql: ["MySQL", "마이에스큐엘"]
  redis: ["Redis", "레디스"]
  mongodb: ["MongoDB", "Mongo", "몽고DB", "몽고디비"]
  kafka: ["Kafka", "Apache Kafka", "카프카"]
  aws: ["AWS", "Amazon Web Services", "아마존 웹 서비스"]
  gcp: ["GCP", "Google Cloud", "Google Cloud Platform", "구글 클라우드"]
  azure: ["Azure", "Microsoft Azure", "애저", "애저 클라우드"]
  docker: ["Docker", "도커"]
  kubernetes: ["Kubernetes", "K8s", "쿠버네티스"]
  terraform: ["Terraform", "테라폼"]
  linux: ["Linux", "리눅스"]
  git: ["Git", "깃"]
  graphql: ["GraphQL", "그래프큐엘", "그래프QL"]
  rest: ["REST", "RESTful", "레스트"]
  spark: ["Spark", "Apache Spark", "스파크"]
  airflow: ["Airflow", "Apache Airflow", "에어플로우"]
  pytorch: ["PyTorch", "파이토치"]
  tensorflow: ["TensorFlow", "텐서플로", "텐서플로우"]
"""
