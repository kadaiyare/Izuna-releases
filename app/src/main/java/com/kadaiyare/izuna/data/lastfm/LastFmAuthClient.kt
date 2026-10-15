package com.kadaiyare.izuna.data.lastfm

import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class LastFmResponse(val status: Int, val body: String) {
    override fun toString() = "LastFmResponse(status=$status, body=redacted)"
}

internal fun interface LastFmTransport {
    suspend fun request(parameters: Map<String, String>): LastFmResponse
}

internal class LastFmHttpsTransport : LastFmTransport {
    override suspend fun request(parameters: Map<String, String>) = withContext(Dispatchers.IO) {
        val connection = URL("https://ws.audioscrobbler.com/2.0/?" + form(parameters))
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(65_537) } ?: throw IOException("Empty authentication response")
            if (bytes.size > 65_536) throw IOException("Authentication response too large")
            LastFmResponse(status, bytes.toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}

internal class LastFmAuthClient(
    private val config: LastFmConfig,
    private val transport: LastFmTransport = LastFmHttpsTransport(),
) : LastFmAuthApi {
    override suspend fun token(): String {
        val value = call(mapOf("method" to "auth.getToken")).getString("token")
        if (!value.isLastFmKey()) throw IOException("Invalid authentication response")
        return value
    }

    override suspend fun session(token: String): StoredLastFmAuth.Connected {
        require(token.isLastFmKey())
        val session = call(mapOf("method" to "auth.getSession", "token" to token)).getJSONObject("session")
        val name = session.getString("name")
        val key = session.getString("key")
        if (name.isBlank() || name.length > 256 || !key.isLastFmKey()) throw IOException("Invalid authentication response")
        return StoredLastFmAuth.Connected(name, key, config.apiKey)
    }

    private suspend fun call(parameters: Map<String, String>): JSONObject {
        val response = transport.request(config.signed(parameters) + ("format" to "json"))
        val json = JSONObject(response.body)
        if (json.has("error")) throw LastFmApiException(json.getInt("error"))
        if (response.status !in 200..299) throw IOException("Authentication HTTP failure")
        return json
    }
}
