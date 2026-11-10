package sh.belfry.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (the NIP-46 session key, the bunker URL) encrypted with an AES-256-GCM key that
 * lives in the Android Keystore and never leaves it; the ciphertext sits in a private
 * SharedPreferences file that is excluded from backups. Values are never logged.
 *
 * [set] throws when the Keystore cannot be used (the caller must not fall back to plain storage);
 * [get] returns null for a missing or unreadable value (e.g. after a restore to another phone).
 */
class Secrets(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun set(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        // the name is bound to the value, so two entries cannot be swapped in the file
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(1 + iv.size + ct.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(ct, 0, packed, 1 + iv.size, ct.size)
        prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val packed = Base64.decode(stored, Base64.NO_WRAP)
            val ivLen = packed[0].toInt()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed, 1, ivLen))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(packed, 1 + ivLen, packed.size - 1 - ivLen), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(name: String): Boolean = prefs.contains(name)

    @Synchronized
    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    /** Sign-out: forget every secret and the key itself. */
    @Synchronized
    fun wipe() {
        prefs.edit().clear().apply()
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // no key yet, or no Keystore: nothing to forget
        }
    }

    companion object {
        const val ALIAS = "belfry.session"
        private const val FILE = "belfry.secrets"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /** Names used by the app. */
        const val SESSION_KEY = "session-key"
        const val BUNKER_URL = "bunker-url"
    }
}
