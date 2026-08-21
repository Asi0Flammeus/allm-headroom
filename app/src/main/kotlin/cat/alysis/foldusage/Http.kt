package cat.alysis.foldusage

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal blocking HTTP GET with bearer auth. Call off the main thread. */
object Http {

    data class Response(val code: Int, val body: String)

    fun get(url: String, token: String): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(code, body)
        } finally {
            conn.disconnect()
        }
    }

    /** code+body on any HTTP status; null message on transport failure. */
    fun describeFailure(e: Exception): String = when (e) {
        is IOException -> e.message ?: "network error"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }
}
