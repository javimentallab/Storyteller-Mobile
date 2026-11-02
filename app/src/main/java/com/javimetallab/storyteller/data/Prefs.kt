package com.javimetallab.storyteller.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Access keys spend the user's money: on disk they are encrypted with a key that lives in the
 * phone's keystore and can't leave it. Backups are also off in the manifest.
 */
private object Secrets {
    private const val ALIAS = "storyteller_api_keys"
    private const val STORE = "AndroidKeyStore"
    private const val MODE = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance(MODE).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }

    /** If it can't be read (the keystore was reset), the user types the key again. */
    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return try {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance(MODE)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}

/** The models, the speed and the bookmarks. Private to the app. */
class Prefs private constructor(context: Context) {
    private val sp = context.getSharedPreferences("storyteller", Context.MODE_PRIVATE)
    private val marks = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) = sp.edit().putFloat("speed", v).apply()

    fun endpoint(c: Capability): Endpoint {
        val o = try {
            JSONObject(sp.getString("ep_" + c.key, "{}").orEmpty())
        } catch (_: Exception) {
            JSONObject()
        }
        return Endpoint(
            url = o.optString("url"),
            key = Secrets.open(o.optString("key")),
            model = o.optString("model"),
            voice = o.optString("voice"),
            format = o.optString("format").ifEmpty { "wav" },
            parallel = o.optInt("parallel", 1).coerceIn(1, MAX_PARALLEL),
            language = o.optString("language").ifEmpty { Languages.default() },
            qc = o.optBoolean("qc", false),
        )
    }

    fun saveEndpoint(c: Capability, e: Endpoint) {
        val o = JSONObject()
            .put("url", e.url)
            .put("key", Secrets.seal(e.key))
            .put("model", e.model)
            .put("voice", e.voice)
            .put("format", e.format)
            .put("parallel", e.parallel)
            .put("language", e.language)
            .put("qc", e.qc)
        sp.edit().putString("ep_" + c.key, o.toString()).apply()
    }

    fun bookmark(bookId: String): Bookmark? = try {
        marks.getString(bookId, null)?.let { Parse.bookmark(JSONObject(it)) }
    } catch (_: Exception) {
        null
    }

    fun saveBookmark(bookId: String, cap: Int, base: String?, pos: Long) {
        val o = JSONObject().put("cap", cap).put("base", base).put("pos", pos)
            .put("updated", System.currentTimeMillis() / 1000.0)
        marks.edit().putString(bookId, o.toString()).apply()
    }

    fun forgetBookmark(bookId: String) {
        marks.edit().remove(bookId).apply()
    }

    companion object {
        const val MAX_PARALLEL = 4

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }
}
