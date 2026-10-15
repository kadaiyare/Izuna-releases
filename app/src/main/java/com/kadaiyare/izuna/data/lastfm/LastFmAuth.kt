package com.kadaiyare.izuna.data.lastfm

import java.net.URLEncoder
import java.security.MessageDigest

internal fun String.isLastFmKey() = matches(Regex("[a-fA-F0-9]{32}"))

class LastFmConfig(val apiKey: String, private val secret: String) {
    val configured get() = apiKey.isLastFmKey() && secret.isLastFmKey()

    fun signed(parameters: Map<String, String>): Map<String, String> {
        check(configured)
        val params = parameters + ("api_key" to apiKey)
        val input = params.toSortedMap().filterKeys { it !in setOf("format", "callback", "api_sig") }
            .entries.joinToString("") { it.key + it.value } + secret
        val signature = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return params + ("api_sig" to signature)
    }

    fun authorizationUrl(token: String): String {
        require(token.isLastFmKey())
        return "https://www.last.fm/api/auth/?" + form(mapOf("api_key" to apiKey, "token" to token))
    }

    override fun toString() = "LastFmConfig(redacted)"
}

internal fun form(parameters: Map<String, String>): String = parameters.entries.joinToString("&") {
    URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
}

sealed class StoredLastFmAuth {
    data object Empty : StoredLastFmAuth()
    class Pending(val token: String, val createdAtMs: Long, val apiKey: String) : StoredLastFmAuth() {
        fun validAt(now: Long) = token.isLastFmKey() && createdAtMs > 0 && now >= createdAtMs && now - createdAtMs < 3_600_000
        override fun toString() = "Pending(redacted)"
    }
    class Connected(val accountName: String, val sessionKey: String, val apiKey: String, val enabledAtMs: Long = 0) : StoredLastFmAuth() {
        override fun toString() = "Connected(redacted)"
    }
}

interface LastFmAuthStore {
    fun read(): StoredLastFmAuth
    fun write(auth: StoredLastFmAuth)
}

interface LastFmAuthApi {
    suspend fun token(): String
    suspend fun session(token: String): StoredLastFmAuth.Connected
}

class LastFmApiException(val code: Int) : Exception("Last.fm authentication failed ($code)")
