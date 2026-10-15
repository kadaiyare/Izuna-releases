package com.kadaiyare.izuna

import com.kadaiyare.izuna.data.listenbrainz.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** Exercises transport configuration and IO failure stages without a real token or network. */
class ListenBrainzTransportTest {
    private class Connection(url: URL) : HttpsURLConnection(url) {
        val body = ByteArrayOutputStream()
        var failure: Boolean? = null
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream("{\"status\":\"ok\"}".toByteArray())
        override fun getOutputStream(): OutputStream {
            if (failure == false) throw IOException("Connection unavailable")
            return if (failure == true) object : OutputStream() {
                override fun write(value: Int) { throw IOException("Write interrupted") }
            } else body
        }
    }
    @Test fun authenticationStaysInHeaderAndRedirectsAreDisabled() = runBlocking {
        lateinit var connection: Connection
        val transport = ListenBrainzHttpsTransport { url -> Connection(url).also { connection = it } }
        val response = transport.request("https://example.test/proxy", "synthetic-token", "/1/submit-listens", "{\"song\":\"架空\"}")
        assertEquals("https://example.test/proxy/1/submit-listens", connection.url.toString())
        assertEquals("Token synthetic-token", connection.getRequestProperty("Authorization"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=UTF-8", connection.getRequestProperty("Content-Type"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout); assertEquals(15_000, connection.readTimeout)
        assertEquals("{\"song\":\"架空\"}", connection.body.toString("UTF-8"))
        assertEquals(200, response.status); assertTrue(connection.closed)
        transport.request("https://example.test", "synthetic-token", "/1/validate-token", null)
        assertEquals("GET", connection.requestMethod); assertEquals(0, connection.body.size())
    }
    @Test fun failuresBeforeAndDuringWriteHaveDifferentRetrySemantics() = runBlocking {
        for (sent in listOf(false, true)) {
            lateinit var connection: Connection
            val transport = ListenBrainzHttpsTransport { url -> Connection(url).also { connection = it; it.failure = sent } }
            try {
                transport.request("https://example.test", "synthetic-token", "/1/submit-listens", "{}")
                fail("Expected failure")
            } catch (failure: ListenBrainzNetworkFailure) { assertEquals(sent, failure.mayHaveSent) }
            assertTrue(connection.closed)
        }
    }
}
