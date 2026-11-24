package com.wanicam.core

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/** HTTP Digest (RFC 2617/7616, MD5 and SHA-256) and Basic, shared by HTTP and RTSP. */
object Digest {
    private val rnd = SecureRandom()

    fun parseChallenge(header: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]*))").findAll(header.substringAfter(' ')).forEach {
            out[it.groupValues[1].lowercase()] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        return out
    }

    private fun hash(alg: String, s: String): String {
        val md = MessageDigest.getInstance(if (alg.startsWith("SHA-256", true)) "SHA-256" else "MD5")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Builds an Authorization header for one of the given WWW-Authenticate challenges. */
    fun authorization(challenges: List<String>, method: String, uri: String, user: String, password: String,
                      nc: Int = 1): String? {
        val digest = challenges.firstOrNull { it.startsWith("Digest", true) }
        if (digest != null) {
            val c = parseChallenge(digest)
            val realm = c["realm"] ?: ""
            val nonce = c["nonce"] ?: return null
            val alg = c["algorithm"] ?: "MD5"
            val ha1 = hash(alg, "$user:$realm:$password")
            val ha2 = hash(alg, "$method:$uri")
            val qop = c["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\"")
            if (qop != null) {
                val ncs = "%08x".format(nc)
                val cnonce = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val resp = hash(alg, "$ha1:$nonce:$ncs:$cnonce:$qop:$ha2")
                sb.append(", response=\"$resp\", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
            } else {
                sb.append(", response=\"${hash(alg, "$ha1:$nonce:$ha2")}\"")
            }
            c["opaque"]?.let { sb.append(", opaque=\"$it\"") }
            c["algorithm"]?.let { sb.append(", algorithm=$it") }
            return sb.toString()
        }
        if (challenges.any { it.startsWith("Basic", true) }) return Credentials.basic(user, password)
        return null
    }
}

/** OkHttp authenticator that answers one Digest or Basic challenge with the user's credentials. */
class CameraAuthenticator(private val user: String, private val password: String) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (user.isEmpty() || response.request.header("Authorization") != null) return null
        val challenges = response.headers("WWW-Authenticate")
        val url = response.request.url
        val uri = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        val auth = Digest.authorization(challenges, response.request.method, uri, user, password) ?: return null
        return response.request.newBuilder().header("Authorization", auth).build()
    }
}

object Net {
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun client(user: String?, password: String?, readTimeoutSec: Long = 10): OkHttpClient =
        base.newBuilder()
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .authenticator(CameraAuthenticator(user ?: "", password ?: ""))
            .build()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Inserts user:password into a URL unless it already has credentials. */
    fun withCreds(url: String, user: String?, password: String?): String {
        if (user.isNullOrEmpty()) return url
        val m = Regex("^(\\w+://)([^/]*)(.*)$").find(url) ?: return url
        val (scheme, authority, rest) = m.destructured
        if (authority.contains('@')) return url
        return "$scheme${enc(user)}:${enc(password ?: "")}@$authority$rest"
    }

    data class Stripped(val url: String, val user: String, val password: String)

    /** Removes user:password from a URL and returns them separately. */
    fun stripCreds(url: String): Stripped {
        val m = Regex("^(\\w+://)([^/@]*)@(.*)$").find(url) ?: return Stripped(url, "", "")
        val (scheme, info, rest) = m.destructured
        val user = URLDecoder.decode(info.substringBefore(':').replace("+", "%2B"), "UTF-8")
        val pass = if (info.contains(':')) URLDecoder.decode(info.substringAfter(':').replace("+", "%2B"), "UTF-8") else ""
        return Stripped("$scheme$rest", user, pass)
    }

    fun hostOf(url: String): String? = try {
        URI(stripCreds(url).url).host
    } catch (e: Exception) {
        null
    }

    /** Replaces the host in a URL (cameras behind NAT sometimes report an internal address). */
    fun replaceHost(url: String, host: String): String =
        Regex("^(\\w+://)(\\[[^]]+]|[^/:]+)").replace(url) { it.groupValues[1] + host }
}
