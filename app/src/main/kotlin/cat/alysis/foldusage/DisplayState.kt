package cat.alysis.foldusage

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure display logic: (stored fetch state, now) -> what the widget shows.
 * No Android types so the whole state machine runs under plain JVM tests.
 */
enum class Freshness { FRESH, STALE, ERROR, NO_DATA, UNCONFIGURED }

/** Remaining-headroom band driving the bar and percent color. */
enum class Headroom { COMFORTABLE, TIGHTENING, NEARLY_SPENT }

data class Row(
    val provider: String, // stable bridge key: "anthropic" / "openai"
    val leader: Boolean, // true on the provider group's first row (renders the provider mark)
    val window: String, // "5h" / "wk" / "Fable wk"; "" on the empty-provider placeholder
    val remainingPercent: Int?, // null => no usable data for this window
    val pacePercent: Int?, // where remaining% would sit right now under even linear spend; null => unknown window length or no reset time
    val resetText: String, // "→ 04:29" / "→ Mon 09:00" / "no data"
)

data class WidgetModel(
    val freshness: Freshness,
    val statusLine: String,
    val rows: List<Row>,
)

/** Background refresh cadence choices and the staleness they imply. */
object Refresh {
    /** 15 min is WorkManager's periodic floor. */
    val CHOICES_MIN = listOf(15, 30, 60, 180)
    const val DEFAULT_MIN = 15

    fun coerce(minutes: Int): Int = if (minutes in CHOICES_MIN) minutes else DEFAULT_MIN

    /** The widget's own data counts as stale once two scheduled refreshes in a row were missed. */
    fun staleAfterMs(minutes: Int): Long = 3L * coerce(minutes) * 60_000L
}

object DisplayState {

    /** Local staleness at the default cadence: how old the widget's OWN last successful fetch may get. */
    val WIDGET_STALE_AFTER_MS: Long = Refresh.staleAfterMs(Refresh.DEFAULT_MIN)

    /** Provider keys in display order. */
    val PROVIDERS = listOf("anthropic", "openai")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

    /**
     * Freshness precedence, most severe first:
     * UNCONFIGURED > NO_DATA > ERROR (last attempt failed; cached values shown)
     * > STALE (server flagged stale, or our own success is old) > FRESH.
     */
    fun computeFreshness(
        nowMs: Long,
        configured: Boolean,
        hasData: Boolean,
        lastSuccessAtMs: Long,
        serverStale: Boolean,
        lastError: String?,
        staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
    ): Freshness = when {
        !configured -> Freshness.UNCONFIGURED
        !hasData -> Freshness.NO_DATA
        lastError != null -> Freshness.ERROR
        serverStale || nowMs - lastSuccessAtMs > staleAfterMs -> Freshness.STALE
        else -> Freshness.FRESH
    }

    fun buildModel(
        nowMs: Long,
        zone: ZoneId,
        configured: Boolean,
        parsed: UsageResponse?,
        lastSuccessAtMs: Long,
        lastError: String?,
        staleAfterMs: Long = WIDGET_STALE_AFTER_MS,
        hidden: Set<String> = emptySet(),
    ): WidgetModel {
        val freshness = computeFreshness(
            nowMs, configured, parsed != null, lastSuccessAtMs,
            parsed?.stale ?: false, lastError, staleAfterMs,
        )
        val asOf = formatClock(lastSuccessAtMs, zone)
        val status = when (freshness) {
            Freshness.UNCONFIGURED -> "tap \u2699 to configure"
            Freshness.NO_DATA -> lastError?.let { "no data \u00b7 $it" } ?: "no data yet"
            Freshness.ERROR -> "offline \u00b7 data from $asOf"
            Freshness.STALE -> staleSinceText(parsed?.staleSince, zone) ?: "stale \u00b7 data from $asOf"
            Freshness.FRESH -> "updated $asOf"
        }
        return WidgetModel(freshness, status, parsed?.let { buildRows(it, nowMs, zone, hidden) } ?: emptyList())
    }

    /** "stale since 12:25" from the server's `stale_since`, when it sent one. */
    fun staleSinceText(staleSince: String?, zone: ZoneId): String? = try {
        staleSince?.let { "stale since ${formatClock(OffsetDateTime.parse(it).toInstant().toEpochMilli(), zone)}" }
    } catch (_: Exception) {
        null
    }

    /**
     * One row per bridge window, in bridge order. Unavailable windows keep their
     * identity (label, falling back to id) as explicit no-data rows instead of
     * being dropped; a provider with no windows at all gets one placeholder row.
     * [hidden] holds provider keys ("openai") and window keys ([windowKey]) the
     * user chose not to show; they produce no rows at all.
     */
    fun buildRows(parsed: UsageResponse, nowMs: Long, zone: ZoneId, hidden: Set<String> = emptySet()): List<Row> {
        val rows = mutableListOf<Row>()
        for (key in PROVIDERS) {
            if (key in hidden) continue
            val all = parsed.providers[key]?.windows.orEmpty()
            if (all.isEmpty()) {
                rows.add(Row(key, true, "", null, null, "no data"))
                continue
            }
            all.filterNot { windowKey(key, it.id) in hidden }.forEachIndexed { i, w ->
                val pct = if (w.available) w.remainingPercent else null
                rows.add(
                    Row(
                        provider = key,
                        leader = i == 0,
                        window = shortLabel(w.label.ifEmpty { w.id }),
                        remainingPercent = pct,
                        pacePercent = if (pct == null) null else paceRemainingPercent(w.id, w.resetsAt, nowMs),
                        resetText = if (pct == null) "no data" else resetText(w.resetsAt, nowMs, zone),
                    )
                )
            }
        }
        return rows
    }

    /** Settings/visibility key of one provider window, e.g. "anthropic/5h". */
    fun windowKey(provider: String, windowId: String): String = "$provider/$windowId"

    /** "just now" / "4 min ago" / "2 h ago" / "3 d ago"; "never" for a zero timestamp. */
    fun ageText(nowMs: Long, thenMs: Long): String {
        if (thenMs <= 0L) return "never"
        val min = (nowMs - thenMs).coerceAtLeast(0L) / 60_000L
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} d ago"
        }
    }

    private fun shortLabel(label: String): String = if (label == "week") "wk" else label

    /** Band boundaries: >30 comfortable, 11–30 tightening, ≤10 nearly spent. */
    fun headroom(remainingPercent: Int): Headroom = when {
        remainingPercent > 30 -> Headroom.COMFORTABLE
        remainingPercent > 10 -> Headroom.TIGHTENING
        else -> Headroom.NEARLY_SPENT
    }

    /**
     * Known window lengths, keyed by the bridge's stable window id (see
     * sub-usage-bridge's norm_window: "5h" for the rolling 5-hour limit,
     * "weekly"/"weekly_scoped_*" for every 7-day limit, regardless of provider).
     * An id outside this map has no known length, so no pace marker is drawn.
     */
    private val WINDOW_DURATION_MS: Map<String, Long> = mapOf(
        "5h" to 5 * 60 * 60 * 1000L,
    )
    private const val WEEKLY_DURATION_MS: Long = 7 * 24 * 60 * 60 * 1000L

    private fun windowDurationMs(windowId: String): Long? = when {
        windowId == "weekly" || windowId.startsWith("weekly_scoped_") -> WEEKLY_DURATION_MS
        else -> WINDOW_DURATION_MS[windowId]
    }

    /**
     * asi0's own straight-line rule of thumb: if this window's quota were spent
     * exactly evenly against its own clock, where would remaining% sit right now?
     * `100 * secondsUntilReset / windowLengthSeconds`, clamped to [0, 100]. A
     * higher live remainingPercent than this means running under budget; lower
     * means burning faster than the linear pace. Null when the window's length
     * or reset time is unknown.
     */
    fun paceRemainingPercent(windowId: String, resetsAt: String?, nowMs: Long): Int? {
        if (resetsAt == null) return null
        val durationMs = windowDurationMs(windowId) ?: return null
        val resetMs = try {
            OffsetDateTime.parse(resetsAt).toInstant().toEpochMilli()
        } catch (_: Exception) {
            return null
        }
        val remainingMs = (resetMs - nowMs).coerceIn(0L, durationMs)
        return ((100.0 * remainingMs / durationMs).toInt()).coerceIn(0, 100)
    }

    /** "→ 04:29" when the reset lands within 24 h, else "→ Mon 09:00" (device-local). */
    fun resetText(resetsAt: String?, nowMs: Long, zone: ZoneId): String {
        if (resetsAt == null) return ""
        val instant = try {
            OffsetDateTime.parse(resetsAt).toInstant()
        } catch (_: Exception) {
            return ""
        }
        val local = instant.atZone(zone)
        val within24h = instant.toEpochMilli() - nowMs <= 24 * 60 * 60 * 1000L
        val text = if (within24h) TIME.format(local) else DAY_TIME.format(local)
        return "\u2192 $text"
    }

    fun formatClock(epochMs: Long, zone: ZoneId): String =
        TIME.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
