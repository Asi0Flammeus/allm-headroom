package cat.alysis.foldusage

import java.net.URI

/** Bridge URL rules: the build's default and Save-time validation. Pure JVM. */
object Endpoint {

    /** Blank unless the build sets `headroom.defaultUrl` in local.properties (never committed). */
    val DEFAULT_URL: String get() = BuildConfig.DEFAULT_BRIDGE_URL

    fun normalize(input: String): String = input.trim().trimEnd('/')

    /** Null when [input] is a usable bridge base URL, else the reason it is not. */
    fun problem(input: String): String? {
        val url = normalize(input)
        if (url.isEmpty()) return "URL is required"
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "not a valid URL"
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return "must start with https://"
        if (uri.host.isNullOrBlank()) return "missing host name"
        return null
    }
}
