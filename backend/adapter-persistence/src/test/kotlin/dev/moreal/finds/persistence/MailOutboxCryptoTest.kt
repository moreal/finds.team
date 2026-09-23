package dev.moreal.finds.persistence

import dev.moreal.finds.application.port.EncryptedMailPayload
import dev.moreal.finds.application.port.MailPayloadMetadata
import dev.moreal.mail.MailMessageId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class MailOutboxCryptoTest {
  @Test
  fun `AES 256 GCM round trips with fresh 96 bit nonces`() {
    val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to KEY))
    val nonces = (1..100).map {
      val encrypted = crypto.encrypt(METADATA, PLAINTEXT)
      assertEquals(12, encrypted.nonce.size)
      assertEquals(PLAINTEXT.size + 16, encrypted.ciphertext.size)
      assertContentEquals(PLAINTEXT, crypto.decrypt(METADATA, encrypted))
      encrypted.nonce.toList()
    }
    assertEquals(100, nonces.toSet().size)
  }

  @Test
  fun `authentication rejects changed id purpose expiry ciphertext nonce and wrong key`() {
    val crypto = AesGcmMailPayloadCrypto(1, mapOf(1 to KEY))
    val encrypted = crypto.encrypt(METADATA, PLAINTEXT)
    listOf(
      METADATA.copy(id = MailMessageId.new()),
      METADATA.copy(purpose = "RECOVERY"),
      METADATA.copy(expiresAt = METADATA.expiresAt.plusSeconds(1)),
    ).forEach { changed -> assertFailsWith<IllegalStateException> { crypto.decrypt(changed, encrypted) } }
    val changedCiphertext = encrypted.ciphertext.also { it[0] = (it[0].toInt() xor 1).toByte() }
    val changedNonce = encrypted.nonce.also { it[0] = (it[0].toInt() xor 1).toByte() }
    assertFailsWith<IllegalStateException> { crypto.decrypt(METADATA, EncryptedMailPayload(changedCiphertext, encrypted.nonce, 1)) }
    assertFailsWith<IllegalStateException> { crypto.decrypt(METADATA, EncryptedMailPayload(encrypted.ciphertext, changedNonce, 1)) }
    assertFailsWith<IllegalStateException> { crypto.decrypt(METADATA, EncryptedMailPayload(encrypted.ciphertext, encrypted.nonce, 2)) }
    assertFailsWith<IllegalStateException> {
      AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(32) { 88 })).decrypt(METADATA, encrypted)
    }
  }

  @Test
  fun `key rotation decrypts old versions and defensively copies external key bytes`() {
    val external = KEY.copyOf()
    val original = AesGcmMailPayloadCrypto(1, mapOf(1 to external))
    val encrypted = original.encrypt(METADATA, PLAINTEXT)
    external.fill(0)
    val rotated = AesGcmMailPayloadCrypto(2, mapOf(1 to KEY, 2 to ByteArray(32) { 88 }))
    assertContentEquals(PLAINTEXT, rotated.decrypt(METADATA, original.encrypt(METADATA, PLAINTEXT)))
    assertContentEquals(PLAINTEXT, rotated.decrypt(METADATA, encrypted))
    assertEquals(2, rotated.encrypt(METADATA, PLAINTEXT).keyVersion)
    assertFalse(encrypted.toString().contains("419573"))
  }

  @Test
  fun `configuration rejects absent active keys and non 256 bit keys`() {
    assertFailsWith<IllegalArgumentException> { AesGcmMailPayloadCrypto(1, emptyMap()) }
    assertFailsWith<IllegalArgumentException> { AesGcmMailPayloadCrypto(1, mapOf(1 to ByteArray(16))) }
    assertFailsWith<IllegalArgumentException> { AesGcmMailPayloadCrypto(0, mapOf(0 to KEY)) }
  }

  private companion object {
    val KEY = ByteArray(32) { (it + 1).toByte() }
    val PLAINTEXT = "수신자 recipient@example.com OTP 419573".encodeToByteArray()
    val METADATA = MailPayloadMetadata(MailMessageId.new(), "VERIFY_EMAIL", Instant.parse("2026-09-23T00:05:00Z"))
  }
}
