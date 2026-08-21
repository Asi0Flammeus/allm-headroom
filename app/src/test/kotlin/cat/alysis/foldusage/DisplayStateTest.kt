package cat.alysis.foldusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The widget's three user-visible paths (fresh / stale / error) plus the two
 * empty states, as pure JVM tests. NOW is fixed; the zone is UTC.
 */
class DisplayStateTest {

    private val now = 1_787_100_000_000L // 2026-08-19T00:40:00Z
    private val zone = ZoneOffset.UTC

    /** Verbatim bridge /usage 200 body captured on 2026-08-19 (openai probe down). */
    private val realBody = """
        {"schema": 1, "generated_at": "2026-08-19T00:15:13Z", "fetched_at": "2026-08-19T00:15:13Z",
         "age_seconds": 0, "stale": false, "stale_after_seconds": 2700, "fetch_error": null,
         "last_attempt_at": "2026-08-19T00:15:05Z",
         "providers": {
           "anthropic": {"ok": true, "windows": [
             {"id": "5h", "label": "5h", "available": true, "used_percent": 13, "remaining_percent": 87,
              "resets_at": "2026-08-19T02:29:59+00:00", "seconds_until_reset": 8094},
             {"id": "weekly", "label": "week", "available": true, "used_percent": 15, "remaining_percent": 85,
              "resets_at": "2026-08-25T06:59:59+00:00", "seconds_until_reset": 542694},
             {"id": "weekly_scoped_0", "label": "Fable wk", "available": true, "used_percent": 16,
              "remaining_percent": 84, "resets_at": "2026-08-25T06:59:59+00:00", "seconds_until_reset": 542694}]},
           "openai": {"ok": true, "windows": [
             {"id": "5h", "label": "5h", "available": false, "used_percent": null, "remaining_percent": null,
              "resets_at": null, "seconds_until_reset": null},
             {"id": "weekly", "label": "week", "available": false, "used_percent": null, "remaining_percent": null,
              "resets_at": null, "seconds_until_reset": null}]}}}
    """.trimIndent()

    private fun freshParsed(stale: Boolean = false) = parseUsage(realBody).copy(stale = stale)

    // --- parsing the real contract ---

    @Test
    fun `parses real bridge body`() {
        val r = parseUsage(realBody)
        assertEquals(false, r.stale)
        assertNull(r.fetchError)
        assertEquals(3, r.providers.getValue("anthropic").windows.size)
        val fable = r.providers.getValue("anthropic").windows[2]
        assertEquals("Fable wk", fable.label)
        assertEquals(84, fable.remainingPercent)
        // unavailable windows keep null percents instead of fake zeros
        val openai5h = r.providers.getValue("openai").windows[0]
        assertEquals(false, openai5h.available)
        assertNull(openai5h.remainingPercent)
    }

    // --- freshness precedence ---

    @Test
    fun `unconfigured wins over everything`() {
        val f = DisplayState.computeFreshness(now, false, true, now, true, "boom")
        assertEquals(Freshness.UNCONFIGURED, f)
    }

    @Test
    fun `no data beats error`() {
        val f = DisplayState.computeFreshness(now, true, false, 0L, false, "HTTP 503")
        assertEquals(Freshness.NO_DATA, f)
    }

    @Test
    fun `failed attempt with cached data is error, not stale`() {
        val f = DisplayState.computeFreshness(now, true, true, now - 60_000, true, "timeout")
        assertEquals(Freshness.ERROR, f)
    }

    @Test
    fun `server stale flag makes stale`() {
        val f = DisplayState.computeFreshness(now, true, true, now - 60_000, true, null)
        assertEquals(Freshness.STALE, f)
    }

    @Test
    fun `old local success makes stale even when server said fresh`() {
        val age = DisplayState.WIDGET_STALE_AFTER_MS + 1
        val f = DisplayState.computeFreshness(now, true, true, now - age, false, null)
        assertEquals(Freshness.STALE, f)
    }

    @Test
    fun `staleness boundary is exclusive`() {
        val f = DisplayState.computeFreshness(
            now, true, true, now - DisplayState.WIDGET_STALE_AFTER_MS, false, null,
        )
        assertEquals(Freshness.FRESH, f)
    }

    @Test
    fun `recent success is fresh`() {
        val f = DisplayState.computeFreshness(now, true, true, now - 60_000, false, null)
        assertEquals(Freshness.FRESH, f)
    }

    // --- full model: the three display paths ---

    @Test
    fun `fresh path shows updated time and every window row`() {
        val m = DisplayState.buildModel(now, zone, true, freshParsed(), now - 5 * 60_000, null)
        assertEquals(Freshness.FRESH, m.freshness)
        assertEquals("updated 00:35", m.statusLine)
        // 3 anthropic windows + 2 openai windows (unavailable, but never dropped)
        assertEquals(5, m.rows.size)
        assertEquals(Row("anthropic", true, "5h", 87, 36, "\u2192 02:29"), m.rows[0])
        // mark only on the group's first row; "week" shortened
        assertEquals(Row("anthropic", false, "wk", 85, 89, "\u2192 Tue 06:59"), m.rows[1])
        assertEquals(Row("anthropic", false, "Fable wk", 84, 89, "\u2192 Tue 06:59"), m.rows[2])
        // unavailable openai windows keep their identity as explicit no-data rows
        assertEquals(Row("openai", true, "5h", null, null, "no data"), m.rows[3])
        assertEquals(Row("openai", false, "wk", null, null, "no data"), m.rows[4])
    }

    @Test
    fun `stale path keeps values and flags them`() {
        val m = DisplayState.buildModel(now, zone, true, freshParsed(stale = true), now - 5 * 60_000, null)
        assertEquals(Freshness.STALE, m.freshness)
        assertEquals("stale \u00b7 data from 00:35", m.statusLine)
        assertEquals(5, m.rows.size) // cached values still visible
    }

    @Test
    fun `error path keeps cached values and reports offline`() {
        val m = DisplayState.buildModel(now, zone, true, freshParsed(), now - 5 * 60_000, "unreachable")
        assertEquals(Freshness.ERROR, m.freshness)
        assertEquals("offline \u00b7 data from 00:35", m.statusLine)
        assertEquals(87, m.rows[0].remainingPercent) // last-good values retained
    }

    @Test
    fun `never-fetched error path has no rows and says why`() {
        val m = DisplayState.buildModel(now, zone, true, null, 0L, "HTTP 401 \u00b7 unauthorized")
        assertEquals(Freshness.NO_DATA, m.freshness)
        assertTrue(m.rows.isEmpty())
        assertTrue(m.statusLine.contains("HTTP 401"))
    }

    @Test
    fun `unconfigured model prompts pairing`() {
        val m = DisplayState.buildModel(now, zone, false, null, 0L, null)
        assertEquals(Freshness.UNCONFIGURED, m.freshness)
        assertTrue(m.statusLine.contains("configure"))
    }

    // --- row identity and headroom bands ---

    @Test
    fun `provider with no windows at all renders one placeholder row`() {
        val body = """{"providers": {"anthropic": {"ok": true, "windows": []}}}"""
        val rows = DisplayState.buildRows(parseUsage(body), now, zone)
        assertEquals(
            listOf(
                Row("anthropic", true, "", null, null, "no data"),
                Row("openai", true, "", null, null, "no data"),
            ),
            rows,
        )
    }

    @Test
    fun `available window with null percent degrades to a no-data row`() {
        val body = """{"providers": {"openai": {"ok": true, "windows": [
            {"id": "5h", "label": "5h", "available": true, "remaining_percent": null}]}}}"""
        val rows = DisplayState.buildRows(parseUsage(body), now, zone)
        assertEquals(Row("openai", true, "5h", null, null, "no data"), rows[1])
    }

    @Test
    fun `window with empty label falls back to its id`() {
        val body = """{"providers": {"openai": {"ok": true, "windows": [
            {"id": "weekly", "label": "", "available": false}]}}}"""
        val rows = DisplayState.buildRows(parseUsage(body), now, zone)
        assertEquals(Row("openai", true, "weekly", null, null, "no data"), rows[1])
    }

    @Test
    fun `headroom bands sit on the 30 and 10 boundaries`() {
        assertEquals(Headroom.COMFORTABLE, DisplayState.headroom(100))
        assertEquals(Headroom.COMFORTABLE, DisplayState.headroom(31))
        assertEquals(Headroom.TIGHTENING, DisplayState.headroom(30))
        assertEquals(Headroom.TIGHTENING, DisplayState.headroom(11))
        assertEquals(Headroom.NEARLY_SPENT, DisplayState.headroom(10))
        assertEquals(Headroom.NEARLY_SPENT, DisplayState.headroom(0))
    }

    // --- linear-pace marker: asi0's straight-line rule (100 * remaining / duration) ---

    @Test
    fun `pace is 100 right after a window starts and 0 right as it resets`() {
        assertEquals(100, DisplayState.paceRemainingPercent("5h", "2026-08-19T05:40:00+00:00", now))
        assertEquals(0, DisplayState.paceRemainingPercent("5h", "2026-08-19T00:40:00+00:00", now))
    }

    @Test
    fun `pace at the exact midpoint of a 5h window is 50`() {
        // 2h30m remaining of a 5h window
        assertEquals(50, DisplayState.paceRemainingPercent("5h", "2026-08-19T03:10:00+00:00", now))
    }

    @Test
    fun `weekly and weekly-scoped ids both use the 7-day window`() {
        val resetsAt = "2026-08-22T00:40:00+00:00" // exactly 3 days out
        val expected = 300 * 100 / 700 // 3 of 7 days, truncated like the implementation
        assertEquals(expected, DisplayState.paceRemainingPercent("weekly", resetsAt, now))
        assertEquals(expected, DisplayState.paceRemainingPercent("weekly_scoped_0", resetsAt, now))
    }

    @Test
    fun `pace is null for an unknown window id or a missing reset time`() {
        assertNull(DisplayState.paceRemainingPercent("monthly", "2026-08-19T05:40:00+00:00", now))
        assertNull(DisplayState.paceRemainingPercent("5h", null, now))
    }

    @Test
    fun `pace is clamped when the reset time has already passed`() {
        assertEquals(0, DisplayState.paceRemainingPercent("5h", "2026-08-18T00:00:00+00:00", now))
    }

    // --- reset time formatting ---

    @Test
    fun `reset within 24h is clock only, beyond is day plus clock`() {
        assertEquals("\u2192 02:29", DisplayState.resetText("2026-08-19T02:29:59+00:00", now, zone))
        assertEquals("\u2192 Tue 06:59", DisplayState.resetText("2026-08-25T06:59:59+00:00", now, zone))
    }

    @Test
    fun `reset formatting respects device zone`() {
        val paris = java.time.ZoneId.of("Europe/Paris")
        assertEquals("\u2192 04:29", DisplayState.resetText("2026-08-19T02:29:59+00:00", now, paris))
    }

    @Test
    fun `unparseable or missing reset is blank`() {
        assertEquals("", DisplayState.resetText(null, now, zone))
        assertEquals("", DisplayState.resetText("not-a-date", now, zone))
    }
}
