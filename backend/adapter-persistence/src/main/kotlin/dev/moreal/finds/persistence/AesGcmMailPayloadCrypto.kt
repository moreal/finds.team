package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.EncryptedMailPayload
import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailPayloadMetadata
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.temporal.ChronoUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keys are loaded by the composition root, never fetched from the database or a remote KMS here. */
class AesGcmMailPayloadCrypto(private val activeKeyVersion: Int, keys: Map<Int, ByteArray>) : MailPayloadCrypto {
  private val keys: Map<Int, SecretKeySpec>
  private val random = SecureRandom()

  init {
    require(activeKeyVersion in keys && keys.all { (version, key) -> version > 0 && key.size == 32 }) {
      "Mail encryption requires versioned 256-bit keys and an active key"
    }
    this.keys = keys.mapValues { (_, key) -> SecretKeySpec(key.copyOf(), "AES") }
  }

  override fun encrypt(metadata: MailPayloadMetadata, plaintext: ByteArray): EncryptedMailPayload {
    val nonce = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, keys.getValue(activeKeyVersion), GCMParameterSpec(128, nonce))
    cipher.updateAAD(aad(metadata))
    return EncryptedMailPayload(cipher.doFinal(plaintext), nonce, activeKeyVersion)
  }

  override fun decrypt(metadata: MailPayloadMetadata, payload: EncryptedMailPayload): ByteArray {
    val key = keys[payload.keyVersion] ?: throw IllegalStateException("Unable to decrypt mail payload")
    try {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.nonce))
      cipher.updateAAD(aad(metadata))
      return cipher.doFinal(payload.ciphertext)
    } catch (_: GeneralSecurityException) {
      // No ciphertext, provider exceptions, or untrusted payload content in diagnostics.
      throw IllegalStateException("Unable to decrypt mail payload")
    }
  }

  private fun aad(metadata: MailPayloadMetadata): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
      output.writeUTF("finds.mail-outbox.v1")
      output.writeLong(metadata.id.value.mostSignificantBits)
      output.writeLong(metadata.id.value.leastSignificantBits)
      output.writeUTF(metadata.purpose)
      // PostgreSQL TIMESTAMPTZ stores microseconds. Enqueue uses this same canonical precision.
      val expiry = metadata.expiresAt.truncatedTo(ChronoUnit.MICROS)
      output.writeLong(expiry.epochSecond)
      output.writeInt(expiry.nano)
    }
    bytes.toByteArray()
  }
}
