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
        assertEquals(197, u.workers["opus"]?.count)
        assertEquals(1148, u.workers["sonnet"]?.count)
        assertEquals(WorkerEstimate(49, 2.0, "Fable", estimate = true), u.workers["fable"])
        assertEquals(false, u.workers["opus"]?.estimate)
    }

    @Test
    fun footerListsOpusSonnetFableWithEstimateMark() {
        val u = parseWeekly(body)
        assertEquals(
            listOf(WorkerCount("197", "Opus"), WorkerCount("1,148", "Sonnet"), WorkerCount("~49", "Fable")),
            WeeklyDisplay.workerCounts(u),
        )
        // An older bridge without Fable: the footer just omits it.
        val old = u.copy(workers = u.workers - "fable")
        assertEquals(listOf("Opus", "Sonnet"), WeeklyDisplay.workerCounts(old).map { it.label })
    }

    @Test
    fun reserveNoteFollowsTheBridge() {
        val u = parseWeekly(body)
        assertEquals(32.28, u.reservePercent!!, 1e-9)
        assertEquals("after 32% reserve", WeeklyDisplay.reserveNote(u))
        // A bridge without a reserve (before calibration): no note.
        assertEquals("", WeeklyDisplay.reserveNote(u.copy(reservePercent = null)))
        assertEquals(null, parseWeekly(org.json.JSONObject(body).apply { remove("reserve") }.toString()).reservePercent)
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
        val week = 7 * 24 * 3_600_000L
        // 3.3 pts of a 168 h window = 5.544 h = 5h33.
        assertEquals("+3.3 pts · 5h33 idle to pace" to true, WeeklyDisplay.paceText(3.26, week))
        assertEquals("-1.5 pts · 2h31 spare" to false, WeeklyDisplay.paceText(-1.5, week))
        assertEquals("on even pace" to null, WeeklyDisplay.paceText(0.4, week))
        assertEquals("6h43", WeeklyDisplay.gapTime(4.0, week))
        // The real window length is used: 4 pts of a 100 h window = 4h00.
        assertEquals("4h00", WeeklyDisplay.gapTime(4.0, 100 * 3_600_000L))
    }

    @Test
    fun dayEndTotalsFollowTheCurve() {
        val u = parseWeekly(body)
        val now = ms("2026-11-18T11:10:00Z")
        assertEquals(u.usedPercent, WeeklyDisplay.weekTotalAtDayEnd(u, 1, now), 1e-9) // today: the live total
        assertEquals(WeeklyDisplay.usedAt(u, ms("2026-11-18T07:00:00Z")), WeeklyDisplay.weekTotalAtDayEnd(u, 0, now), 1e-9)
        assertEquals(ms("2026-11-18T07:00:00Z"), WeeklyDisplay.dayEndMs(u, 0, now))
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
        assertEquals("+3.2 pts · 5h23 idle to pace", v.paceText) // 20 % used vs 16.8 % pace at 28h10 of 168 h
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
