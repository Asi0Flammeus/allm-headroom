package cat.alysis.foldusage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/** `/usage/weekly` parsing and the weekly widget's text: pure JVM, against a real bridge sample. */
class WeeklyModelTest {

    private val body = javaClass.getResource("/weekly-sample.json")!!.readText()
    private val paris = ZoneId.of("Europe/Paris")
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test
    fun parsesWindowFromResetNotWeekday() {
        val u = parseWeekly(body)
        assertEquals(ms("2026-11-17T07:00:00Z"), u.startMs)
        assertEquals(ms("2026-11-24T07:00:00Z"), u.resetMs)
        assertEquals(7, u.days.size)
        assertEquals(listOf("main", "workers", "sentinels", "t3", "alfred"), u.types.map { it.id })
        assertEquals(377, u.workers["opus"]?.count)
        assertEquals(1656, u.workers["sonnet"]?.count)
    }

    @Test
    fun dayTypesSumToDayDelta() {
        for (d in parseWeekly(body).days) {
            assertEquals(d.label, d.deltaPercent, d.byType.values.sum(), 1e-9)
        }
    }

    @Test
    fun paceComparesUsageToTheStraightLine() {
        val u = parseWeekly(body)
        assertEquals(0.0, WeeklyDisplay.evenPacePercent(u, u.startMs), 1e-9)
        assertEquals(50.0, WeeklyDisplay.evenPacePercent(u, (u.startMs + u.resetMs) / 2), 1e-9)
        assertEquals(100.0, WeeklyDisplay.evenPacePercent(u, u.resetMs + 1), 1e-9)
        assertEquals("3.3 pts over even pace" to true, WeeklyDisplay.paceText(3.26))
        assertEquals("1.5 pts under even pace" to false, WeeklyDisplay.paceText(-1.5))
        assertEquals("on even pace" to null, WeeklyDisplay.paceText(0.4))
    }

    @Test
    fun viewTextsHaveNoDashes() {
        val u = parseWeekly(body)
        val now = ms("2026-11-18T11:10:00Z")
        val v = WeeklyDisplay.build(now, paris, true, u, now - 60_000, null)
        assertEquals(Freshness.FRESH, v.freshness)
        assertEquals("20% used", v.usedText)
        assertEquals("resets Tue 08:00", v.resetText)
        assertEquals("updated 12:09", v.statusLine)
        assertTrue(v.paceText.endsWith("over even pace"))
        val all = listOf(v.usedText, v.paceText, v.resetText, v.statusLine) + v.legend.map { it.first.label }
        for (s in all) assertTrue(s, s.none { it == '\u2014' || it == '\u2013' })
        assertEquals("1,656", WeeklyDisplay.fmtCount(1656))
    }

    @Test
    fun legendTotalsAreTheSumOfDays() {
        val u = parseWeekly(body)
        val totals = WeeklyDisplay.typeTotals(u).associate { (t, v) -> t.id to v }
        assertEquals(u.days.sumOf { it.byType["workers"] ?: 0.0 }, totals.getValue("workers"), 1e-9)
        val unattributed = u.days.sumOf { it.byType["unattributed"] ?: 0.0 }
        assertEquals(u.days.sumOf { it.deltaPercent }, totals.values.sum() + unattributed, 1e-9)
    }

    @Test
    fun errorKeepsLastDataAndSaysSo() {
        val u = parseWeekly(body)
        val now = ms("2026-11-18T11:10:00Z")
        val v = WeeklyDisplay.build(now, paris, true, u, now - 3_600_000, "timeout")
        assertEquals(Freshness.ERROR, v.freshness)
        assertEquals("offline, data from 11:10", v.statusLine)
        val none = WeeklyDisplay.build(now, paris, false, null, 0, null)
        assertEquals(Freshness.UNCONFIGURED, none.freshness)
        assertNull(none.usage)
    }

    @Test
    fun historyKeepsOneEntryPerWindowNewestFirst() {
        val u = parseWeekly(body)
        val once = WeeklyHistory.merge(null, u)
        val twice = WeeklyHistory.merge(once, u.copy(usedPercent = 25.0))
        val older = u.copy(resetMs = u.resetMs - 7 * 86_400_000L, usedPercent = 100.0)
        val all = WeeklyHistory.parse(WeeklyHistory.merge(twice, older))
        assertEquals(listOf(u.resetMs, older.resetMs), all.map { it.resetMs })
        assertEquals(25.0, all.first().usedPercent, 1e-9)
    }
}
