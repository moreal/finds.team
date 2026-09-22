package dev.moreal.finds.source.robots

class RobotsPolicy private constructor(
  private val groups: List<Group>,
  val sitemaps: List<String>,
) {
  fun allows(productToken: String, pathAndQuery: String): Boolean {
    if (pathAndQuery.substringBefore('?') == "/robots.txt") return true
    val product = productToken.lowercase()
    val exactGroups = groups.filter { product in it.agents }
    val applicableGroups = exactGroups.ifEmpty {
      groups.filter { "*" in it.agents }
    }
    val rules = applicableGroups.flatMap(Group::rules)
    val normalizedPath = normalizePercentEncoding(pathAndQuery.ifEmpty { "/" })
    val matches = rules.filter { it.matches(normalizedPath) }
    val longest = matches.maxOfOrNull(Rule::specificity) ?: return true
    return matches.filter { it.specificity == longest }.any(Rule::allow)
  }

  private data class Group(
    val agents: List<String>,
    val rules: List<Rule>,
  )

  private data class Rule(
    val allow: Boolean,
    val pattern: Regex,
    val specificity: Int,
  ) {
    fun matches(path: String): Boolean = pattern.containsMatchIn(path)
  }

  companion object {
    fun parse(content: String): RobotsPolicy {
      val groups = mutableListOf<Group>()
      val sitemaps = mutableListOf<String>()
      var agents = mutableListOf<String>()
      var rules = mutableListOf<Rule>()

      fun flushGroup() {
        if (agents.isNotEmpty()) {
          groups += Group(agents.toList(), rules.toList())
        }
        agents = mutableListOf()
        rules = mutableListOf()
      }

      content.lineSequence().forEach { rawLine ->
        val line = rawLine.substringBefore('#').trim()
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val field = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        when (field) {
          "user-agent" -> {
            if (rules.isNotEmpty()) flushGroup()
            value.lowercase()
              .takeIf { it == "*" || PRODUCT_TOKEN.matches(it) }
              ?.let(agents::add)
          }
          "allow", "disallow" -> {
            if (agents.isEmpty() || value.isEmpty()) return@forEach
            rules += rule(allow = field == "allow", value = value)
          }
          "sitemap" -> value.takeIf(String::isNotEmpty)?.let(sitemaps::add)
        }
      }
      flushGroup()
      return RobotsPolicy(groups, sitemaps.distinct())
    }

    private fun rule(allow: Boolean, value: String): Rule {
      val normalized = normalizePercentEncoding(value)
      val anchored = normalized.endsWith('$')
      val source = if (anchored) normalized.dropLast(1) else normalized
      val regex = source.split('*').joinToString(".*") { Regex.escape(it) }
      return Rule(
        allow = allow,
        pattern = Regex("^$regex${if (anchored) "$" else ""}"),
        specificity = source.count { it != '*' },
      )
    }

    private val PRODUCT_TOKEN = Regex("[a-z_-]+")
  }
}

private fun normalizePercentEncoding(value: String): String {
  val output = StringBuilder(value.length)
  var index = 0
  while (index < value.length) {
    if (value[index] == '%' && index + 2 < value.length) {
      val hex = value.substring(index + 1, index + 3)
      val octet = hex.toIntOrNull(16)
      if (octet != null) {
        val character = octet.toChar()
        if (octet.isAsciiUnreserved()) {
          output.append(character)
        } else {
          output.append('%').append(hex.uppercase())
        }
        index += 3
        continue
      }
    }
    output.append(value[index])
    index += 1
  }
  return output.toString()
}

private fun Int.isAsciiUnreserved(): Boolean =
  this in 'A'.code..'Z'.code ||
    this in 'a'.code..'z'.code ||
    this in '0'.code..'9'.code ||
    this.toChar() in "-._~"
