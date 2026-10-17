package cat.alysis.foldusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Bridge URL migration/validation, failure reasons, refresh cadence and age text: pure JVM. */
class SettingsTest {

    // --- retired-host migration ---

    @Test
    fun `default points at maia-server`() {
        assertEquals("https://maia-server.tail58339.ts.net:8377", Endpoint.DEFAULT_URL)
    }

    @Test
    fun `saved crqpt-server URL migrates to maia-server`() {
        assertEquals(
            "https://maia-server.tail58339.ts.net:8377",
            Endpoint.migrated("https://crqpt-server.tail58339.ts.net:8377"),
        )
    }

    @Test
    fun `migration keeps scheme port and path and ignores host case`() {
        assertEquals(
            "http://maia-server.tail58339.ts.net:9000/bridge",
            Endpoint.migrated("http://CRQPT-SERVER.tail58339.ts.net:9000/bridge/"),
        )
    }

    @Test
    fun `current or custom URLs are left alone`() {
        assertNull(Endpoint.migrated("https://maia-server.tail58339.ts.net:8377"))
        assertNull(Endpoint.migrated("https://example.org"))
        assertNull(Endpoint.migrated(""))
        assertNull(Endpoint.migrated("not a url ::"))
    }

    // --- Save-time validation ---

    @Test
    fun `validation accepts the default and names what is wrong otherwise`() {
        assertNull(Endpoint.problem(Endpoint.DEFAULT_URL))
        assertNull(Endpoint.problem("  https://maia-server.tail58339.ts.net:8377/  "))
        assertEquals("URL is required", Endpoint.problem("   "))
        assertEquals("must start with https://", Endpoint.problem("maia-server.tail58339.ts.net:8377"))
        assertEquals("missing host name", Endpoint.problem("https:///usage"))
        assertEquals("not a valid URL", Endpoint.problem("https://maia server"))
    }

    // --- failure reasons shown by Test and in the status pane ---

    @Test
    fun `transport failures name timeout and DNS`() {
        assertTrue(Http.describeFailure(SocketTimeoutException("connect timed out")).startsWith("timeout"))
        assertTrue(Http.describeFailure(UnknownHostException("nope.ts.net")).startsWith("DNS"))
        assertEquals(
            "unreachable \u00b7 Connection refused",
            Http.describeFailure(ConnectException("Connection refused")),
        )
    }

    @Test
    fun `HTTP failures name the cause and keep the bridge detail`() {
        assertEquals("HTTP 401 \u00b7 bad token", Http.describeStatus(401, """{"error": "unauthorized"}"""))
        assertEquals("HTTP 404 \u00b7 not a Headroom bridge (check the URL)", Http.describeStatus(404, "<html>"))
        assertEquals(
            "HTTP 503 \u00b7 bridge has no usage data yet \u00b7 probe failed",
            Http.describeStatus(503, """{"error": "probe failed", "last_attempt_at": null}"""),
        )
        assertEquals("HTTP 500", Http.describeStatus(500, ""))
    }

    // --- refresh cadence ---

    @Test
    fun `refresh cadence is coerced to a known choice and sets staleness`() {
        assertEquals(15, Refresh.coerce(7))
        assertEquals(60, Refresh.coerce(60))
        assertEquals(45 * 60_000L, Refresh.staleAfterMs(15))
        assertEquals(9 * 60 * 60_000L, Refresh.staleAfterMs(180))
        assertEquals(DisplayState.WIDGET_STALE_AFTER_MS, Refresh.staleAfterMs(Refresh.DEFAULT_MIN))
    }

    @Test
    fun `age text`() {
        val now = 1_787_100_000_000L
        assertEquals("never", DisplayState.ageText(now, 0L))
        assertEquals("just now", DisplayState.ageText(now, now - 30_000L))
        assertEquals("4 min ago", DisplayState.ageText(now, now - 4 * 60_000L))
        assertEquals("2 h ago", DisplayState.ageText(now, now - 125 * 60_000L))
        assertEquals("3 d ago", DisplayState.ageText(now, now - 3 * 24 * 60 * 60_000L))
    }
}
