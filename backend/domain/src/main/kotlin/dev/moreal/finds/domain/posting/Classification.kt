package dev.moreal.finds.domain.posting

import java.util.Collections
import java.util.Locale

enum class SkillRequirementLevel { REQUIRED, PREFERRED, MENTIONED }
enum class RoleCategory { BACKEND, FRONTEND, FULLSTACK, MOBILE, DATA, DEVOPS, DESIGN, PRODUCT, QA, UNKNOWN }
enum class EmploymentType { FULL_TIME, PART_TIME, CONTRACT, INTERNSHIP, UNKNOWN }
enum class RemotePolicy { REMOTE, HYBRID, ONSITE, UNKNOWN }
data class ClassifiedValue<T>(val value: T, val rawValue: String?)
data class NormalizedLocation(val displayName: String, val searchValue: String)
data class SkillMention(val slug: String?, val text: String, val level: SkillRequirementLevel)
class PostingClassification(
  val taxonomyVersion: Int,
  skills: List<SkillMention>,
  val role: ClassifiedValue<RoleCategory>,
  val employment: ClassifiedValue<EmploymentType>,
  val remote: ClassifiedValue<RemotePolicy>,
  val location: NormalizedLocation?,
) {
  val skills: List<SkillMention> = Collections.unmodifiableList(skills.toList())

  override fun equals(other: Any?): Boolean = other is PostingClassification &&
    taxonomyVersion == other.taxonomyVersion && skills == other.skills && role == other.role &&
    employment == other.employment && remote == other.remote && location == other.location

  override fun hashCode(): Int = listOf(taxonomyVersion, skills, role, employment, remote, location).hashCode()
}

/**
 * Hints are authoritative for employment/remote/location; absent, conflicting or unsupported
 * hints remain UNKNOWN with their original value. Role uses title only. Unknown locations
 * retain a whitespace-normalized display value and a locale-independent lowercase search key.
 * Skills use section headings, then clause hints (preferred before required), then MENTIONED.
 * Repeated canonical skills keep the strongest level. Unrecognized clauses in skill/qualification
 * sections are retained with a null slug as evidence, not asserted to be canonical skills.
 */
fun classifyPosting(raw: RawPosting, taxonomy: SkillTaxonomy = SkillTaxonomy.V1): PostingClassification {
  val known = linkedMapOf<String, SkillMention>()
  val unknown = linkedSetOf<SkillMention>()
  var section = SkillRequirementLevel.MENTIONED
  var retainUnknown = false
  (raw.title + "\n" + raw.descriptionText).lineSequence().forEach { original ->
    val line = original.trim().removePrefix("#").trimStart('#', ' ', '*', '-', '•').trimEnd('*')
    val heading = line.substringBefore(':').trim().lowercase(Locale.ROOT)
    val headingLevel = HEADINGS[heading]
    val recognizedHeading = headingLevel != null
    if (recognizedHeading) {
      section = requireNotNull(headingLevel)
      retainUnknown = heading !in RESET_HEADINGS
    }
    val content = if (recognizedHeading) line.substringAfter(':', "") else line
    content.split(Regex("""[,;/]|\s+(?:and|및)\s+""", RegexOption.IGNORE_CASE))
      .map(String::trim).filter(String::isNotEmpty).forEach { clause ->
      val level = when {
        NEGATED_REQUIREMENT.containsMatchIn(clause) -> SkillRequirementLevel.MENTIONED
        PREFERRED_HINT.containsMatchIn(clause) -> SkillRequirementLevel.PREFERRED
        REQUIRED_HINT.containsMatchIn(clause) -> SkillRequirementLevel.REQUIRED
        else -> section
      }
      val found = taxonomy.findMentions(clause, retainUnknown || level != SkillRequirementLevel.MENTIONED)
      found.forEach { (skill, text) ->
        val previous = known[skill.slug]
        if (previous == null || level.ordinal < previous.level.ordinal) {
          known[skill.slug] = SkillMention(skill.slug, text, level)
        }
      }
      if (found.isEmpty() && retainUnknown) unknown += SkillMention(null, clause, level)
    }
  }
  val title = raw.title.lowercase(Locale.ROOT)
  val roles = ROLE_PATTERNS.filterValues { it.containsMatchIn(title) }.keys
  return PostingClassification(
    taxonomy.version,
    known.values.toList() + unknown,
    ClassifiedValue(roles.singleOrNull() ?: RoleCategory.UNKNOWN, raw.title),
    ClassifiedValue(EMPLOYMENT[raw.employmentHint?.hintKey()] ?: EmploymentType.UNKNOWN, raw.employmentHint),
    ClassifiedValue(REMOTE[raw.remoteHint?.hintKey()] ?: RemotePolicy.UNKNOWN, raw.remoteHint),
    raw.locationHint?.compactWhitespace()?.takeIf(String::isNotEmpty)?.let { display ->
      val key = display.lowercase(Locale.ROOT)
      NormalizedLocation(display, LOCATIONS[key] ?: key)
    },
  )
}

private fun String.compactWhitespace(): String = trim().replace(Regex("\\s+"), " ")
private fun String.hintKey(): String = compactWhitespace().lowercase(Locale.ROOT).replace('_', '-')

private val RESET_HEADINGS = setOf("responsibilities", "what you will do", "benefits", "about us", "담당업무", "주요업무", "복리후생", "회사소개")
private val HEADINGS = buildMap {
  listOf("requirements", "required", "qualifications", "minimum qualifications", "자격요건", "자격 요건",
    "필수요건", "필수 요건", "지원자격").forEach { put(it, SkillRequirementLevel.REQUIRED) }
  listOf("preferred", "preferred qualifications", "nice to have", "우대사항", "우대 사항")
    .forEach { put(it, SkillRequirementLevel.PREFERRED) }
  listOf("skills", "tech stack", "기술스택", "기술 스택", "사용 기술")
    .forEach { put(it, SkillRequirementLevel.MENTIONED) }
  RESET_HEADINGS.forEach { put(it, SkillRequirementLevel.MENTIONED) }
}
private val PREFERRED_HINT = Regex("""(?i)\b(preferred|nice to have|optional)\b|우대""")
private val REQUIRED_HINT = Regex("""(?i)\b(required|must have|essential)\b|필수""")
private val NEGATED_REQUIREMENT = Regex("""(?i)\bnot (?:required|essential)\b|필수(?:가)?\s*아""")

private val ROLE_PATTERNS = mapOf(
  RoleCategory.BACKEND to Regex("""\b(back[- ]?end|server[- ]side)\b|백엔드|서버 개발"""),
  RoleCategory.FRONTEND to Regex("""\b(front[- ]?end)\b|프론트엔드"""),
  RoleCategory.FULLSTACK to Regex("""\bfull[- ]?stack\b|풀스택"""),
  RoleCategory.MOBILE to Regex("""\b(mobile|ios|android|flutter)\b|모바일|안드로이드"""),
  RoleCategory.DATA to Regex("""\b(data|machine learning|ml|ai)\b|데이터|머신러닝|인공지능"""),
  RoleCategory.DEVOPS to Regex("""\b(devops|sre|infrastructure)\b|인프라"""),
  RoleCategory.DESIGN to Regex("""\b(designer|design)\b|디자이너"""),
  RoleCategory.PRODUCT to Regex("""\b(product manager|product owner)\b|프로덕트 매니저|기획자"""),
  RoleCategory.QA to Regex("""\b(qa|quality assurance|test engineer)\b|품질"""),
)
private val EMPLOYMENT = mapOf(
  "full-time" to EmploymentType.FULL_TIME, "full time" to EmploymentType.FULL_TIME, "정규직" to EmploymentType.FULL_TIME,
  "part-time" to EmploymentType.PART_TIME, "part time" to EmploymentType.PART_TIME, "파트타임" to EmploymentType.PART_TIME,
  "contract" to EmploymentType.CONTRACT, "contractor" to EmploymentType.CONTRACT, "계약직" to EmploymentType.CONTRACT,
  "internship" to EmploymentType.INTERNSHIP, "intern" to EmploymentType.INTERNSHIP, "인턴" to EmploymentType.INTERNSHIP,
)
private val REMOTE = mapOf(
  "remote" to RemotePolicy.REMOTE, "fully remote" to RemotePolicy.REMOTE, "재택근무" to RemotePolicy.REMOTE,
  "원격근무" to RemotePolicy.REMOTE, "hybrid" to RemotePolicy.HYBRID, "하이브리드" to RemotePolicy.HYBRID,
  "onsite" to RemotePolicy.ONSITE, "on-site" to RemotePolicy.ONSITE, "출근" to RemotePolicy.ONSITE,
  "사무실 근무" to RemotePolicy.ONSITE,
)
private val LOCATIONS = mapOf(
  "서울" to "seoul", "서울특별시" to "seoul", "seoul" to "seoul",
  "부산" to "busan", "부산광역시" to "busan", "busan" to "busan",
  "경기" to "gyeonggi", "경기도" to "gyeonggi", "gyeonggi" to "gyeonggi",
)

private fun SkillTaxonomy.findMentions(text: String, explicitSkills: Boolean): List<Pair<SkillDefinition, String>> =
  skills.mapNotNull { skill ->
    skill.aliases.asSequence().sortedByDescending(String::length).mapNotNull { alias ->
      // Punctuation forms are atomic: C must not match C++ or C#, and Go must not match Go-to-market.
      // Korean postpositions are recognized explicitly, not by dropping Unicode word boundaries.
      val suffix = """|(?:과|와|을|를|은|는|이|가|로|으로)(?=\s|$)|\.(?=\s|$)"""
      val pattern = Regex("""(?<![\p{L}\p{N}_+#.])${Regex.escape(alias)}(?=$|[^\p{L}\p{N}_+#.\-]$suffix)""",
        RegexOption.IGNORE_CASE)
      pattern.findAll(text).firstOrNull { match ->
        explicitSkills || when (alias) {
          "Go" -> match.value == alias && (
            text.trim() == alias || Regex("""(?i)\b(go (?:developer|engineer|programming|language)|(?:in|using) go)\b""").containsMatchIn(text)
          )
          "React", "Spring", "REST" -> match.value == alias
          else -> true
        }
      }?.value
    }.firstOrNull()?.let { skill to it }
  }
