package cat.alysis.foldusage

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One usage type of the weekly view, in the bridge's stacking order (bottom first). */
data class WeeklyType(val id: String, val label: String)

/** One 24 h slice of the weekly window (`/usage/weekly` days[]). [byType] sums to [deltaPercent]. */
data class WeeklyDay(
    val label: String, // "Tue": weekday of the slice start, in the bridge's timezone
    val state: String, // complete | partial | future
    val deltaPercent: Double,
    val byType: Map<String, Double>, // type id (plus "unattributed") -> % of the weekly quota
)

/** Workers of one model family the remaining quota still pays for; null count = not calibrated. */
data class WorkerEstimate(val count: Int?, val pctPerWorker: Double?, val governedBy: String?)

/** Parsed `/usage/weekly` body. Times are epoch milliseconds. */
data class WeeklyUsage(
    val startMs: Long,
    val resetMs: Long,
    val usedPercent: Double,
    val curve: List<Pair<Long, Double>>, // (epoch ms, cumulative used %), oldest first
    val types: List<WeeklyType>,
    val days: List<WeeklyDay>,
    val workers: Map<String, WorkerEstimate>,
    val stale: Boolean,
    val staleSince: String?,
)

private fun parseInstantMs(s: String): Long = OffsetDateTime.parse(s).toInstant().toEpochMilli()

/** Parse a bridge `/usage/weekly` 200 body. Throws on a body the widget cannot draw. */
fun parseWeekly(body: String): WeeklyUsage {
    val o = JSONObject(body)
    val w = o.getJSONObject("window")
    val curveJson = o.getJSONArray("curve")
    val curve = (0 until curveJson.length()).map { i ->
        val p = curveJson.getJSONArray(i)
        p.getLong(0) * 1000L to p.getDouble(1)
    }
    val typesJson = o.getJSONArray("types")
    val types = (0 until typesJson.length()).map { i ->
        val t = typesJson.getJSONObject(i)
        WeeklyType(t.getString("id"), t.optString("label", t.getString("id")))
    }
    val daysJson = o.getJSONArray("days")
    val days = (0 until daysJson.length()).map { i ->
        val d = daysJson.getJSONObject(i)
        val by = d.optJSONObject("by_type")
        WeeklyDay(
            label = d.optString("label"),
            state = d.optString("state", "future"),
            deltaPercent = if (d.isNull("delta_percent")) 0.0 else d.optDouble("delta_percent", 0.0),
            byType = by?.keys()?.asSequence()?.associateWith { by.optDouble(it, 0.0) } ?: emptyMap(),
        )
    }
    val workersJson = o.optJSONObject("workers_left")
    val workers = workersJson?.keys()?.asSequence()?.associateWith { k ->
        val e = workersJson.getJSONObject(k)
        WorkerEstimate(
            count = if (e.isNull("count")) null else e.optInt("count"),
            pctPerWorker = if (e.isNull("pct_per_worker")) null else e.optDouble("pct_per_worker"),
            governedBy = if (e.isNull("governed_by")) null else e.optString("governed_by"),
        )
    } ?: emptyMap()
    return WeeklyUsage(
        startMs = parseInstantMs(w.getString("start")),
        resetMs = parseInstantMs(w.getString("resets_at")),
        usedPercent = w.getDouble("used_percent"),
        curve = curve,
        types = types,
        days = days,
        workers = workers,
        stale = o.optBoolean("stale", false),
        staleSince = if (o.isNull("stale_since")) null else o.optString("stale_since"),
    )
}

/** What the weekly widget shows besides the chart. Pure JVM. */
data class WeeklyView(
    val freshness: Freshness,
    val statusLine: String, // "updated 11:03" / "stale since 10:40" / "offline, data from 09:12"
    val usedText: String, // "20% used"
    val paceText: String, // "3.3 pts over even pace" / "1.2 pts under even pace" / "on even pace"
    val overPace: Boolean?, // null when on pace or unknown
    val resetText: String, // "resets Tue 08:00"
    val legend: List<Pair<WeeklyType, Double>>, // each type's share of the week so far
    val opus: Int?,
    val sonnet: Int?,
    val usage: WeeklyUsage?,
)

object WeeklyDisplay {

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    /** Where cumulative use would sit now under even spend over the window: 0 at start, 100 at reset. */
    fun evenPacePercent(u: WeeklyUsage, nowMs: Long): Double {
        val span = (u.resetMs - u.startMs).coerceAtLeast(1L)
        return (100.0 * (nowMs - u.startMs) / span).coerceIn(0.0, 100.0)
    }

    /** Each type's total over the window so far, in the bridge's type order. */
    fun typeTotals(u: WeeklyUsage): List<Pair<WeeklyType, Double>> =
        u.types.map { t -> t to u.days.sumOf { it.byType[t.id] ?: 0.0 } }

    fun paceText(deltaPts: Double): Pair<String, Boolean?> {
        val rounded = (abs(deltaPts) * 10).roundToInt() / 10.0
        return when {
            rounded < 0.5 -> "on even pace" to null
            deltaPts > 0 -> "${fmt1(rounded)} pts over even pace" to true
            else -> "${fmt1(rounded)} pts under even pace" to false
        }
    }

    fun fmt1(v: Double): String = String.format(Locale.ENGLISH, "%.1f", v)

    fun fmtCount(n: Int): String = String.format(Locale.ENGLISH, "%,d", n)

    fun build(
        nowMs: Long,
        zone: ZoneId,
        configured: Boolean,
        parsed: WeeklyUsage?,
        lastSuccessAtMs: Long,
        lastError: String?,
        staleAfterMs: Long = DisplayState.WIDGET_STALE_AFTER_MS,
    ): WeeklyView {
        val freshness = DisplayState.computeFreshness(
            nowMs, configured, parsed != null, lastSuccessAtMs, parsed?.stale ?: false, lastError, staleAfterMs,
        )
        val asOf = clock(lastSuccessAtMs, zone)
        val status = when (freshness) {
            Freshness.UNCONFIGURED -> "tap to pair a bridge"
            Freshness.NO_DATA -> lastError ?: "no data yet"
            Freshness.ERROR -> "offline, data from $asOf"
            Freshness.STALE -> DisplayState.staleSinceText(parsed?.staleSince, zone) ?: "stale, data from $asOf"
            Freshness.FRESH -> "updated $asOf"
        }
        if (parsed == null) {
            return WeeklyView(freshness, status, "", "", null, "", emptyList(), null, null, null)
        }
        val (pace, over) = paceText(parsed.usedPercent - evenPacePercent(parsed, nowMs))
        return WeeklyView(
            freshness = freshness,
            statusLine = status,
            usedText = "${parsed.usedPercent.roundToInt()}% used",
            paceText = pace,
            overPace = over,
            resetText = "resets ${DAY_TIME.format(Instant.ofEpochMilli(parsed.resetMs).atZone(zone))}",
            legend = typeTotals(parsed),
            opus = parsed.workers["opus"]?.count,
            sonnet = parsed.workers["sonnet"]?.count,
            usage = parsed,
        )
    }

    private fun clock(ms: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** One line per weekly window this phone has seen, kept as a JSON array in prefs. Pure JVM. */
object WeeklyHistory {
    const val KEEP = 12

    data class Entry(val resetMs: Long, val usedPercent: Double, val byType: Map<String, Double>)

    fun parse(json: String?): List<Entry> = try {
        val a = org.json.JSONArray(json ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val by = o.getJSONObject("by_type")
            Entry(o.getLong("reset_ms"), o.getDouble("used_percent"), by.keys().asSequence().associateWith { by.getDouble(it) })
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** [old] with the entry for [u]'s window replaced by its latest totals; newest first, at most [KEEP]. */
    fun merge(old: String?, u: WeeklyUsage): String {
        val now = Entry(u.resetMs, u.usedPercent, WeeklyDisplay.typeTotals(u).associate { (t, v) -> t.id to v })
        val merged = (listOf(now) + parse(old).filterNot { it.resetMs == u.resetMs })
            .sortedByDescending { it.resetMs }
            .take(KEEP)
        return org.json.JSONArray(merged.map { e ->
            JSONObject().put("reset_ms", e.resetMs).put("used_percent", e.usedPercent)
                .put("by_type", JSONObject(e.byType.mapValues { (it.value * 100).roundToInt() / 100.0 }))
        }).toString()
    }
}
