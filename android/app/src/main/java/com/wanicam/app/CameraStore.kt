package com.wanicam.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Camera(
    val id: String = UUID.randomUUID().toString().take(10),
    val name: String,
    /** rtsp | mjpeg | snapshot | hls | rtmp | other */
    val kind: String,
    val url: String,
    val user: String = "",
    val password: String = "",
    val brand: String? = null,
    val host: String? = null,
    /** tcp | udp (RTSP only) */
    val transport: String = "tcp",
) {
    val kindLabel: String
        get() = when (kind) {
            "rtsp" -> "RTSP"; "mjpeg" -> "MJPEG"; "snapshot" -> "Snapshot"; "hls" -> "HLS"; "rtmp" -> "RTMP"
            else -> "Stream"
        }

    companion object {
        /** Guesses the stream type from a URL. */
        fun kindFor(url: String): String {
            val u = url.trim().lowercase()
            return when {
                u.startsWith("rtsp") -> "rtsp"
                u.startsWith("rtmp") -> "rtmp"
                u.contains(".m3u8") -> "hls"
                Regex("\\.(jpe?g|png)(\\?|$)|snapshot|snap|picture|image\\.cgi|shot\\.jpg").containsMatchIn(u) -> "snapshot"
                u.startsWith("http") && Regex("mjpe?g|video\\.cgi|videostream|faststream|/video$|stream").containsMatchIn(u) -> "mjpeg"
                else -> "other"
            }
        }
    }
}

/** Camera passwords are encrypted with a key kept in the Android Keystore, which never leaves the device. */
private object Secret {
    private const val ALIAS = "wanicam-passwords"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            val data = Base64.decode(enc, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12))
        } catch (e: Exception) {
            ""
        }
    }
}

object CameraStore {
    private lateinit var prefs: SharedPreferences
    private val cams = mutableListOf<Camera>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("cameras", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("list", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            cams += Camera(
                id = o.getString("id"), name = o.optString("name"), kind = o.optString("kind", "rtsp"),
                url = o.optString("url"), user = o.optString("user"),
                password = Secret.decrypt(o.optString("password")),
                brand = o.optString("brand").ifEmpty { null }, host = o.optString("host").ifEmpty { null },
                transport = o.optString("transport", "tcp"),
            )
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (c in cams) arr.put(JSONObject().apply {
            put("id", c.id); put("name", c.name); put("kind", c.kind); put("url", c.url); put("user", c.user)
            put("password", Secret.encrypt(c.password)); put("brand", c.brand ?: ""); put("host", c.host ?: "")
            put("transport", c.transport)
        })
        prefs.edit().putString("list", arr.toString()).apply()
    }

    fun all(): List<Camera> = synchronized(cams) { cams.toList() }

    fun get(id: String?): Camera? = synchronized(cams) { cams.firstOrNull { it.id == id } }

    fun upsert(c: Camera) = synchronized(cams) {
        val i = cams.indexOfFirst { it.id == c.id }
        if (i >= 0) cams[i] = c else cams += c
        save()
    }

    fun delete(id: String) = synchronized(cams) {
        cams.removeAll { it.id == id }
        save()
    }

    fun move(from: Int, to: Int) = synchronized(cams) {
        if (from in cams.indices && to in cams.indices) {
            cams.add(to, cams.removeAt(from))
            save()
        }
    }
}
