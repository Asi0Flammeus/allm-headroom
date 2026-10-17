package cat.alysis.foldusage

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Minimal blocking HTTP GET with bearer auth. Call off the main thread. */
object Http {

    const val CONNECT_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 15_000

    data class Response(val code: Int, val body: String)

    fun get(url: String, token: String): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(code, body)
        } finally {
            conn.disconnect()
        }
    }

    /** One-line reason for a transport failure, naming the likely cause. */
    fun describeFailure(e: Exception): String = when (e) {
        is SocketTimeoutException -> "timeout \u00b7 no answer within ${CONNECT_TIMEOUT_MS / 1000} s (is Tailscale connected?)"
        is UnknownHostException -> "DNS \u00b7 cannot resolve host (Tailscale off or wrong hostname)"
        is ConnectException, is NoRouteToHostException -> "unreachable \u00b7 ${e.message ?: "connection refused"}"
        is SSLException -> "TLS \u00b7 ${e.message ?: "handshake failed"}"
        is MalformedURLException -> "invalid URL \u00b7 ${e.message ?: "unparseable"}"
        is IOException -> e.message ?: "network error"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    /** One-line reason for a non-200 bridge answer, using the bridge's own `error` field when present. */
    fun describeStatus(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optString("error").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val meaning = when (code) {
            401, 403 -> "bad token"
            404 -> "not a Headroom bridge (check the URL)"
            503 -> "bridge has no usage data yet"
            else -> null
        }
        return listOfNotNull("HTTP $code", meaning, detail?.takeIf { it != "unauthorized" }).joinToString(" \u00b7 ")
    }
}
