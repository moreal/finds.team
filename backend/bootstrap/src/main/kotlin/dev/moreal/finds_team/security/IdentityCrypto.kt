package dev.moreal.finds_team.security

import dev.moreal.finds.application.port.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class JvmSecureRandom : SecureRandomPort {
  private val random = SecureRandom()
  override fun bytes(size: Int) = ByteArray(size).also(random::nextBytes)
  override fun nextInt(bound: Int) = random.nextInt(bound)
  override fun uuid(): UUID = UUID.randomUUID()
}

/** Derive independent purpose keys from versioned loaded entropy; never retain or log plaintext. */
class VersionedIdentityHashes(private val active: Int, keys: Map<Int, ByteArray>, private val commandScopeVersion: Int = 1) : KeyedIdentityHashPort {
  private val keys = keys.mapValues { (_, key) -> IdentityHashPurpose.entries.associateWith { purpose ->
    hmac(key, "finds.identity.key.${purpose.name}".toByteArray())
  } }
  init { require(active in this.keys && commandScopeVersion in this.keys && keys.values.all { it.size >= 32 }) }
  override fun hash(purpose: IdentityHashPurpose, binding: String, value: String): KeyedIdentityHash {
    val version = if (purpose == IdentityHashPurpose.COMMAND_SCOPE) commandScopeVersion else active
    return KeyedIdentityHash(version, digest(version, purpose, binding, value))
  }
  override fun matches(expected: KeyedIdentityHash, purpose: IdentityHashPurpose, binding: String, value: String): Boolean =
    expected.pepperVersion in keys && MessageDigest.isEqual(expected.bytes, digest(expected.pepperVersion, purpose, binding, value))
  private fun digest(version: Int, purpose: IdentityHashPurpose, binding: String, value: String): ByteArray {
    val parts = listOf(purpose.name, binding, value).map { it.toByteArray(Charsets.UTF_8) }
    val framed = ByteBuffer.allocate(parts.sumOf { 4 + it.size })
    parts.forEach { framed.putInt(it.size).put(it) }
    return hmac(checkNotNull(keys[version]?.get(purpose)), framed.array())
  }
  private fun hmac(key: ByteArray, value: ByteArray) = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(value)
  }
}
