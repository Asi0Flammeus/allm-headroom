package cat.alysis.foldusage

import java.net.URI

/** Bridge URL rules: the default, retired-host migration, and Save-time validation. Pure JVM. */
object Endpoint {

    const val DEFAULT_HOST = "maia-server.tail58339.ts.net"
    const val DEFAULT_URL = "https://$DEFAULT_HOST:8377"

    /** Hosts the bridge used to live on. A saved URL on one of them is dead and gets rehomed. */
    private val RETIRED_HOSTS = setOf("crqpt-server.tail58339.ts.net")

    /**
     * The URL to store instead of [saved], or null when [saved] needs no change.
     * Only the host moves; scheme, port and path stay as the user saved them.
     */
    fun migrated(saved: String): String? {
        val uri = try {
            URI(saved.trim())
        } catch (_: Exception) {
            return null
        }
        if (uri.host?.lowercase() !in RETIRED_HOSTS) return null
        return URI(uri.scheme, uri.userInfo, DEFAULT_HOST, uri.port, uri.path, uri.query, uri.fragment)
            .toString().trimEnd('/')
    }

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
