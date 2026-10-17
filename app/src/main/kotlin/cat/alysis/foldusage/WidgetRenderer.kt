package cat.alysis.foldusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import java.time.ZoneId

/** Builds RemoteViews from the stored fetch state and pushes them to every widget instance. */
object WidgetRenderer {

    private const val FRESH_COLOR = 0xFF4ADE80.toInt()
    private const val STALE_COLOR = 0xFFFBBF24.toInt()
    private const val ERROR_COLOR = 0xFFF87171.toInt()
    private const val NEUTRAL_COLOR = 0xFF9CA3AF.toInt()

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, UsageWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = build(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    fun build(context: Context): RemoteViews {
        val store = UsageStore(context)
        val parsed = store.lastBody?.let {
            try {
                parseUsage(it)
            } catch (_: Exception) {
                null
            }
        }
        val model = DisplayState.buildModel(
            nowMs = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            configured = store.isConfigured,
            parsed = parsed,
            lastSuccessAtMs = store.lastSuccessAtMs,
            lastError = store.lastError,
            staleAfterMs = Refresh.staleAfterMs(store.refreshMinutes),
            hidden = store.hidden,
        )

        val root = RemoteViews(context.packageName, R.layout.widget_usage)
        root.removeAllViews(R.id.rows)
        for (group in model.rows.groupConsecutiveBy { it.provider }) {
            root.addView(R.id.rows, buildGroup(context, group))
        }

        val dotColor = when (model.freshness) {
            Freshness.FRESH -> FRESH_COLOR
            Freshness.STALE -> STALE_COLOR
            Freshness.ERROR -> ERROR_COLOR
            Freshness.NO_DATA, Freshness.UNCONFIGURED -> NEUTRAL_COLOR
        }
        root.setTextColor(R.id.status_dot, dotColor)
        root.setTextViewText(R.id.status_text, model.statusLine)
        root.setTextColor(
            R.id.status_text,
            if (model.freshness == Freshness.FRESH) NEUTRAL_COLOR else dotColor,
        )

        // The panel is the glance; a tap opens the explanatory app view.
        val dashboard = Intent(context, HeadroomActivity::class.java)
        root.setOnClickPendingIntent(
            R.id.widget_root,
            PendingIntent.getActivity(
                context, 0, dashboard,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        val configure = Intent(context, ConfigActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        root.setOnClickPendingIntent(
            R.id.btn_settings,
            PendingIntent.getActivity(
                context, 1, configure,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        return root
    }

    /** Five stops make urgency deepen continuously below the 30% protect-capacity trigger. */
    private val ALL_BARS = intArrayOf(R.id.row_bar_ok, R.id.row_bar_warn, R.id.row_bar_orange, R.id.row_bar_low, R.id.row_bar_critical)

    /** One provider's block: the mark centered next to its stacked rows. */
    private fun buildGroup(context: Context, rows: List<Row>): RemoteViews {
        val group = RemoteViews(context.packageName, R.layout.widget_provider_group)
        val (markRes, markDesc) = when (rows.first().provider) {
            "anthropic" -> R.drawable.ic_mark_anthropic to "Anthropic"
            "openai" -> R.drawable.ic_mark_openai to "OpenAI"
            else -> R.drawable.ic_mark_anthropic to ""
        }
        group.setImageViewResource(R.id.group_mark, markRes)
        group.setContentDescription(R.id.group_mark, markDesc)
        for (row in rows) group.addView(R.id.group_rows, buildRowLine(context, row))
        return group
    }

    private fun buildRowLine(context: Context, row: Row): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_row_line)
        v.setTextViewText(R.id.row_window, row.window)

        val pct = row.remainingPercent
        if (pct != null) {
            val active = barId(pct)
            for (id in ALL_BARS) v.setViewVisibility(id, if (id == active) View.VISIBLE else View.GONE)
            v.setProgressBar(active, 100, pct, false)
            v.setTextViewText(R.id.row_percent, "$pct%")
            v.setTextColor(R.id.row_percent, percentColor(pct))
            setPaceMarker(v, row.pacePercent)
        } else {
            // Explicit no-data row: window identity stays visible, bar disappears.
            for (id in ALL_BARS) v.setViewVisibility(id, View.GONE)
            v.setTextViewText(R.id.row_percent, "\u2014")
            v.setTextColor(R.id.row_percent, NEUTRAL_COLOR)
            setPaceMarker(v, null)
        }
        v.setTextViewText(R.id.row_reset, row.resetText)
        return v
    }

    /**
     * asi0's straight-line pace tick: where remaining% would sit right now
     * under even linear spend. Positioned purely by ImageView level (0-10000
     * over pace_marker_scale's <scale>+<layer-list>) — no pixel math, no API
     * version dependency. Hidden for no-data rows or windows with no known
     * length (level is undefined either way).
     */
    private fun setPaceMarker(v: RemoteViews, pace: Int?) {
        if (pace == null) {
            v.setViewVisibility(R.id.row_pace_marker, View.GONE)
            return
        }
        v.setViewVisibility(R.id.row_pace_marker, View.VISIBLE)
        v.setInt(R.id.row_pace_marker, "setImageLevel", pace * 100)
    }

    /** The layout pre-tints five stops; RemoteViews selects the right stop without API-specific tinting. */
    private fun barId(remaining: Int): Int = when {
        remaining > 30 -> R.id.row_bar_ok
        remaining > 20 -> R.id.row_bar_warn
        remaining > 10 -> R.id.row_bar_orange
        remaining > 5 -> R.id.row_bar_low
        else -> R.id.row_bar_critical
    }

    private fun percentColor(remaining: Int): Int = when {
        remaining > 30 -> Color.WHITE
        remaining > 20 -> STALE_COLOR
        remaining > 10 -> 0xFFFB923C.toInt()
        remaining > 5 -> ERROR_COLOR
        else -> 0xFFDC2626.toInt()
    }
}

/** Splits a list into runs of consecutive elements sharing the same key, in order. */
private fun <T, K> List<T>.groupConsecutiveBy(key: (T) -> K): List<List<T>> {
    val groups = mutableListOf<MutableList<T>>()
    for (item in this) {
        val lastGroup = groups.lastOrNull()
        if (lastGroup != null && key(lastGroup.first()) == key(item)) {
            lastGroup.add(item)
        } else {
            groups.add(mutableListOf(item))
        }
    }
    return groups
}
