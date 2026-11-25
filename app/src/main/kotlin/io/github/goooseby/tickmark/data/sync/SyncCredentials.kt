package io.github.goooseby.tickmark.data.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Identity and refresh request keys must be durable before network calls.
@android.annotation.SuppressLint("ApplySharedPref")
class SyncIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("tickmark_sync_identity", Context.MODE_PRIVATE)

    fun installId(): String = preferences.getString("installId", null) ?: UUID.randomUUID().toString().also {
        preferences.edit(commit = true) { putString("installId", it) }
    }
}

@android.annotation.SuppressLint("ApplySharedPref")
class SyncTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("tickmark_sync_secure", Context.MODE_PRIVATE)
    private val accessTokens = mutableMapOf<String, AccessToken>()
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    fun saveRefresh(accountId: String, token: String, requestId: String = UUID.randomUUID().toString()) {
        require(token.isNotBlank() && token.length <= 16_384)
        val encrypted = encrypt(token)
        preferences.edit(commit = true) {
            putString("refresh:$accountId", encrypted)
            putString("refreshRequest:$accountId", requestId)
        }
    }

    @Synchronized
    fun refreshToken(accountId: String): String? = runCatching {
        preferences.getString("refresh:$accountId", null)?.let(::decrypt)
    }.getOrNull()

    @Synchronized
    fun refreshRequestId(accountId: String): String {
        val existing = preferences.getString("refreshRequest:$accountId", null)
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        preferences.edit(commit = true) { putString("refreshRequest:$accountId", generated) }
        return generated
    }

    @Synchronized
    fun saveAccess(accountId: String, token: String, expiresAt: Long) {
        require(token.isNotBlank() && token.length <= 16_384)
        accessTokens[accountId] = AccessToken(token, expiresAt)
    }

    @Synchronized
    fun accessToken(accountId: String, now: Long = System.currentTimeMillis()): String? =
        accessTokens[accountId]?.takeIf { it.expiresAt > now + 30_000 }?.token

    @Synchronized
    fun clearAccess(accountId: String) {
        accessTokens.remove(accountId)
    }

    @Synchronized
    fun clear(accountId: String) {
        accessTokens.remove(accountId)
        preferences.edit(commit = true) {
            remove("refresh:$accountId")
            remove("refreshRequest:$accountId")
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private data class AccessToken(val token: String, val expiresAt: Long)

    private companion object {
        const val KEY_ALIAS = "tickmark.sync.refresh.v1"
    }
}
