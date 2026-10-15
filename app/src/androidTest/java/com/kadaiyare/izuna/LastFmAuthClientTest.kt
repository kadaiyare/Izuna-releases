package com.kadaiyare.izuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kadaiyare.izuna.data.lastfm.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LastFmAuthClientTest {
    private val config = LastFmConfig("0".repeat(32), "2".repeat(32))

    @Test fun requestsTokenAndSessionWithSignedParameters() = runBlocking {
        val requests = mutableListOf<Map<String, String>>()
        val client = LastFmAuthClient(config, LastFmTransport { params ->
            requests += params
            LastFmResponse(200, if (params["method"] == "auth.getToken") """{"token":"${"1".repeat(32)}"}"""
            else """{"session":{"name":"テストユーザー","key":"${"3".repeat(32)}","subscriber":0}}""")
        })
        val token = client.token()
        val session = client.session(token)
        assertEquals("テストユーザー", session.accountName)
        assertEquals("3".repeat(32), session.sessionKey)
        assertEquals("json", requests[0]["format"])
        assertEquals("a8f46bba7170fc966a71b18cb21f263c", requests[1]["api_sig"])
        assertFalse(requests.any { it.containsKey("secret") || it.containsKey("password") })
    }

    @Test fun handlesApiErrorsEvenWithHttpSuccess() = runBlocking {
        for (status in listOf(200, 403)) {
            val client = LastFmAuthClient(config, LastFmTransport { LastFmResponse(status, """{"error":14,"message":"not authorized"}""") })
            try { client.session("1".repeat(32)); fail("API error must not connect") }
            catch (failure: LastFmApiException) { assertEquals(14, failure.code) }
        }
    }

    @Test fun rejectsMalformedSuccessAndHttpFailure() = runBlocking {
        for (response in listOf(
            LastFmResponse(200, """{"token":"invalid"}"""),
            LastFmResponse(200, """{"session":{"name":"","key":"invalid"}}"""),
            LastFmResponse(503, """{"token":"${"1".repeat(32)}"}"""),
        )) {
            val client = LastFmAuthClient(config, LastFmTransport { response })
            try {
                if (response.body.contains("session")) client.session("1".repeat(32)) else client.token()
                fail("Invalid response must not connect")
            } catch (_: IOException) { }
        }
    }
}
