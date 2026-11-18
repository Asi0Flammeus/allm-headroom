package cat.alysis.foldusage

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.RemoteViews
import java.time.ZoneId

/** The larger Anthropic-only weekly widget. Shares the fetch worker and pairing with [UsageWidgetProvider]. */
class WeeklyWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WeeklyWidgetRenderer.render(context, appWidgetManager, id)
        FetchUsageWorker.schedulePeriodic(context)
        FetchUsageWorker.refreshNow(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        WeeklyWidgetRenderer.render(context, manager, id) // the chart bitmap follows the new size
    }

    override fun onEnabled(context: Context) {
        FetchUsageWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        if (!WidgetRenderer.hasInstances(context)) FetchUsageWorker.cancelPeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && WeeklyWidgetRenderer.hasInstances(context)) {
            FetchUsageWorker.schedulePeriodic(context)
            WeeklyWidgetRenderer.updateAll(context)
        }
    }
}

object WeeklyWidgetRenderer {

    private const val INK = 0xFFEEF2EF.toInt()
    private const val MUTED = 0xFF8E9993.toInt()
    private const val OK = 0xFF6FD3A0.toInt()
    private const val WARN = 0xFFF2B45C.toInt()
    private const val BAD = 0xFFF2877F.toInt()

    /** Default 5x3 size on the Fold inner screen when the launcher has not reported one yet. */
    private const val DEFAULT_W_DP = 560
    private const val DEFAULT_H_DP = 390

    /** Height taken by everything but the chart: padding, two header lines, legend, divider, footer. */
    private const val CHROME_H_DP = 122

    /** Keeps the RemoteViews bitmap well under the launcher's memory cap. */
    private const val MAX_CHART_PX = 1_200_000

    fun hasInstances(context: Context): Boolean = ids(context).isNotEmpty()

    private fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, WeeklyWidgetProvider::class.java))

    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        for (id in ids(context)) render(context, manager, id)
    }

    fun render(context: Context, manager: AppWidgetManager, id: Int) {
        val (wDp, hDp) = sizeDp(context, manager.getAppWidgetOptions(id))
        manager.updateAppWidget(id, build(context, wDp, hDp))
    }

    /** Portrait: min width x max height; landscape: max width x min height (AppWidgetManager's convention). */
    private fun sizeDp(context: Context, o: Bundle): Pair<Int, Int> {
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val w = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val h = o.getInt(if (portrait) AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        return (if (w > 0) w else DEFAULT_W_DP) to (if (h > 0) h else DEFAULT_H_DP)
    }

    fun build(context: Context, wDp: Int, hDp: Int): RemoteViews {
        val store = UsageStore(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val parsed = store.weeklyBody?.let { runCatching { parseWeekly(it) }.getOrNull() }
        val view = WeeklyDisplay.build(
            nowMs = now, zone = zone, configured = store.isConfigured, parsed = parsed,
            lastSuccessAtMs = store.weeklySuccessAtMs, lastError = store.weeklyError,
            staleAfterMs = Refresh.staleAfterMs(store.refreshMinutes),
        )
        val v = RemoteViews(context.packageName, R.layout.widget_weekly)
        v.setTextViewText(R.id.weekly_used, view.usedText)
        v.setTextViewText(R.id.weekly_pace, view.paceText)
        v.setTextColor(R.id.weekly_pace, when (view.overPace) { true -> BAD; false -> OK; null -> MUTED })
        v.setTextViewText(R.id.weekly_reset, view.resetText)
        v.setTextViewText(R.id.weekly_legend, legend(view))
        v.setTextViewText(R.id.weekly_workers, workers(view))
        v.setTextViewText(R.id.weekly_status, view.statusLine)
        v.setTextColor(R.id.weekly_status_dot, when (view.freshness) {
            Freshness.FRESH -> OK
            Freshness.STALE -> WARN
            Freshness.ERROR -> BAD
            Freshness.NO_DATA, Freshness.UNCONFIGURED -> MUTED
        })

        val u = view.usage
        if (u != null) {
            val density = context.resources.displayMetrics.density
            var wPx = (wDp - 28) * density
            var hPx = (hDp - CHROME_H_DP).coerceAtLeast(60) * density
            val scale = kotlin.math.sqrt(MAX_CHART_PX / (wPx * hPx)).coerceAtMost(1f)
            wPx *= scale; hPx *= scale
            v.setImageViewBitmap(R.id.weekly_chart, WeeklyChart.render(u, now, wPx.toInt(), hPx.toInt(), density * scale))
        }

        // Tap: the full-screen interactive chart once paired (own task: back returns to the
        // home screen), the pairing screen before.
        val target = if (store.isConfigured) Intent(context, WeeklyChartActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        else Intent(context, ConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        v.setOnClickPendingIntent(
            R.id.weekly_root,
            PendingIntent.getActivity(context, 10, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        return v
    }

    private fun legend(view: WeeklyView): CharSequence {
        val sb = SpannableStringBuilder()
        for ((type, total) in view.legend) {
            if (sb.isNotEmpty()) sb.append("   ")
            val sq = sb.length
            sb.append("\u25A0 ")
            sb.setSpan(ForegroundColorSpan(WeeklyChart.colorOf(type.id)), sq, sq + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(type.label).append(' ')
            val n = sb.length
            sb.append("${WeeklyDisplay.fmt1(total)}%")
            sb.setSpan(ForegroundColorSpan(INK), n, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** "Left this week: 374 Opus · 1,632 Sonnet · ~101 Fable" ("~": too few workers measured). */
    fun workers(view: WeeklyView): CharSequence {
        if (view.usage == null) return ""
        if (view.workers.isEmpty() || view.workers.all { it.count == "?" }) return "Workers left: not calibrated yet"
        val sb = SpannableStringBuilder("Left this week: ")
        view.workers.forEachIndexed { i, w ->
            if (i > 0) sb.append(" · ")
            val s = sb.length
            sb.append(w.count)
            sb.setSpan(ForegroundColorSpan(INK), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.45f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ').append(w.label)
        }
        if (view.reserveNote.isNotEmpty()) sb.append(" · ").append(view.reserveNote)
        return sb
    }
}
