package com.aharon.message.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class IdentityManager(context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aharon_message_identity_v1"
        private const val PREFS = "identity"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_DISPLAY_NAME = "display_name"
        private const val HKDF_INFO = "AharonMessage/contact-key/v1"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    val deviceUuid: UUID by lazy {
        val existing = prefs.getString(PREF_DEVICE_ID, null)
        if (existing != null) UUID.fromString(existing) else UUID.randomUUID().also {
            prefs.edit().putString(PREF_DEVICE_ID, it.toString()).apply()
        }
    }

    var displayName: String
        get() = prefs.getString(PREF_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: "Aharon ${deviceUuid.toString().take(4).uppercase()}"
        set(value) {
            prefs.edit().putString(PREF_DISPLAY_NAME, value.trim().take(32)).apply()
        }

    init {
        ensureIdentityKey()
    }

    fun publicKeyBytes(): ByteArray = keyStore().getCertificate(KEY_ALIAS).publicKey.encoded

    fun publicKeyBase64(): String = Base64.encodeToString(publicKeyBytes(), Base64.NO_WRAP)

    fun transportId(): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes())
        return ByteBuffer.wrap(digest.copyOfRange(0, 8)).long and Long.MAX_VALUE
    }

    fun parsePublicKey(base64: String): PublicKey = parsePublicKey(Base64.decode(base64, Base64.NO_WRAP))

    fun parsePublicKey(bytes: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

    fun deriveContactKey(remotePublicKey: ByteArray): ByteArray {
        val privateKey = keyStore().getKey(KEY_ALIAS, null)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(parsePublicKey(remotePublicKey), true)
        val sharedSecret = agreement.generateSecret()

        val local = publicKeyBytes()
        val saltMaterial = if (compareLexicographically(local, remotePublicKey) <= 0) {
            local + remotePublicKey
        } else {
            remotePublicKey + local
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(saltMaterial)
        return hkdfSha256(sharedSecret, salt, HKDF_INFO.toByteArray(), 32)
    }

    fun verificationCode(remotePublicKey: ByteArray): String {
        val local = publicKeyBytes()
        val joined = if (compareLexicographically(local, remotePublicKey) <= 0) {
            local + remotePublicKey
        } else {
            remotePublicKey + local
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(joined)
        val value = (ByteBuffer.wrap(hash.copyOfRange(0, 4)).int.toLong() and 0xffffffffL) % 1_000_000
        return value.toString().padStart(6, '0').chunked(3).joinToString(" ")
    }

    fun encrypt(remotePublicKey: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val key = SecretKeySpec(deriveContactKey(remotePublicKey), "AES")
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(remotePublicKey: ByteArray, encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size >= 12 + 16) { "Encrypted payload is too short" }
        val nonce = encrypted.copyOfRange(0, 12)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size)
        val key = SecretKeySpec(deriveContactKey(remotePublicKey), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    fun newMessageId(): Long = secureRandom.nextLong() and Long.MAX_VALUE

    private fun ensureIdentityKey() {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val output = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val count = minOf(previous.size, length - offset)
            previous.copyInto(output, offset, 0, count)
            offset += count
            counter++
        }
        return output
    }

    private fun compareLexicographically(a: ByteArray, b: ByteArray): Int {
        val count = minOf(a.size, b.size)
        for (index in 0 until count) {
            val av = a[index].toInt() and 0xff
            val bv = b[index].toInt() and 0xff
            if (av != bv) return av - bv
        }
        return a.size - b.size
    }
}
