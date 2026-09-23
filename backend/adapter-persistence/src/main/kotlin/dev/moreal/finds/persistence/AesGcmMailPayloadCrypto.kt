package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.EncryptedMailPayload
import dev.moreal.finds.application.port.MailPayloadCrypto
import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.mail.MailProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.Mac
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

  override fun fingerprintReceipt(provider: MailProvider, receipt: String, keyVersion: Int): String {
    val key = keys[keyVersion] ?: throw IllegalStateException("Unable to fingerprint mail receipt")
    val derive = Mac.getInstance("HmacSHA256")
    derive.init(SecretKeySpec(key.encoded, "HmacSHA256"))
    val subkey = derive.doFinal("finds.mail.receipt-key.v1".encodeToByteArray())
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(subkey, "HmacSHA256"))
    subkey.fill(0)
    val encoded = ByteArrayOutputStream().use { bytes ->
      DataOutputStream(bytes).use { output ->
        // Length-prefix all fields, including the protocol domain, to avoid concatenation ambiguity.
        listOf("finds.mail.receipt.v1", provider.value, receipt).forEach { value ->
          val utf8 = value.encodeToByteArray()
          output.writeInt(utf8.size)
          output.write(utf8)
        }
      }
      bytes.toByteArray()
    }
    return "hmac-sha256:v$keyVersion:${HexFormat.of().formatHex(mac.doFinal(encoded))}"
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
