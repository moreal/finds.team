package dev.moreal.finds_team.security

import java.net.URI
import java.util.Base64
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("finds.security")
class SecurityProperties(
  val rpId: String = "",
  val allowedOrigins: Set<String> = emptySet(),
  val adminEmails: Set<String> = emptySet(),
  val activeHashVersion: Int = 1,
  val commandScopeHashVersion: Int = 1,
  val hashKeys: Map<Int, String> = emptyMap(),
  val trustedProxyCidrs: Set<String> = emptySet(),
) {
  fun validated(profiles: Set<String>): RelyingPartySettings {
    val local = profiles.isNotEmpty() && profiles.all { it in setOf("dev", "test") }
    val rp = rpId.ifEmpty { if (local) "localhost" else "" }
    val origins = allowedOrigins.ifEmpty { if (local) setOf("https://localhost:8443") else emptySet() }
    require(rp == "localhost" && local || rp.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")) &&
      rp.any { it in 'a'..'z' } && rp.split('.').all { it.length <= 63 } && rp.length <= 253) { "Invalid WebAuthn RP ID" }
    require(origins.isNotEmpty()) { "Explicit HTTPS WebAuthn origins are required" }
    origins.forEach { value ->
      val origin = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Invalid WebAuthn origin") }
      require(origin.scheme == "https" && origin.host != null && origin.rawUserInfo == null &&
        origin.rawPath.isNullOrEmpty() && origin.rawQuery == null && origin.rawFragment == null &&
        (origin.port == -1 || origin.port in 1..65535) &&
        (origin.host == rp || origin.host.endsWith(".$rp")) &&
        origin.host == origin.host.lowercase()) { "Invalid HTTPS WebAuthn origin or RP binding" }
    }
    require(activeHashVersion > 0 && (hashKeys.containsKey(activeHashVersion) || local && hashKeys.isEmpty())) { "Identity hash key is required" }
    require(commandScopeHashVersion > 0 && (hashKeys.containsKey(commandScopeHashVersion) ||
      local && hashKeys.isEmpty() && commandScopeHashVersion == activeHashVersion)) { "Stable command-scope hash key is required" }
    hashKeys.forEach { (version, encoded) ->
      val key = try { Base64.getDecoder().decode(encoded) } catch (_: Exception) { throw IllegalArgumentException("Invalid identity hash key") }
      try { require(version > 0 && key.size >= 32) { "Identity hash keys require at least 256 bits" } } finally { key.fill(0) }
    }
    return RelyingPartySettings(rp, origins)
  }
}
data class RelyingPartySettings(val rpId: String, val allowedOrigins: Set<String>)
