package cat.alysis.foldusage

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Full-screen, touchable view of the weekly chart, opened by tapping the weekly widget.
 * Same `/usage/weekly` data the widget stored. Touch or drag across the chart to inspect
 * a day. Leaves with the system back gesture or the Close button; it runs in its own task,
 * so leaving returns to the home screen the widget was tapped from.
 */
class WeeklyChartActivity : Activity() {

    private lateinit var store: UsageStore
    private lateinit var chart: WeeklyChartView
    private lateinit var used: TextView
    private lateinit var pace: TextView
    private lateinit var reset: TextView
    private lateinit var detail: TextView
    private lateinit var legend: TextView
    private lateinit var workers: TextView
    private lateinit var note: TextView
    private lateinit var status: TextView
    private val zone: ZoneId = ZoneId.systemDefault()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> bind() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        store = UsageStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(16))
        }
        root.addView(page, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))

        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text("Claude this week", 22f, INK, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        used = text("", 22f, INK, bold = true)
        head.addView(used)
        val close = text("\u2715  Close", 15f, INK, bold = true).apply {
            contentDescription = "Close full screen chart"
            setPadding(dp(16), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(0x26FFFFFF)
            }
            isClickable = true
            setOnClickListener { finish() }
        }
        head.addView(close, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        page.addView(head)

        val sub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(8)) }
        pace = text("", 15f, MUTED)
        sub.addView(pace, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        reset = text("", 15f, MUTED)
        sub.addView(reset)
        page.addView(sub)

        chart = WeeklyChartView(this) { bindDetail() }
        page.addView(chart, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        detail = text("", 15f, MUTED).apply { setPadding(0, dp(10), 0, 0); minLines = 2 }
        page.addView(detail)
        legend = text("", 13f, MUTED).apply { setPadding(0, dp(8), 0, 0) }
        page.addView(legend)
        page.addView(View(this).apply { setBackgroundColor(0x1FFFFFFF) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10) })
        workers = text("", 16f, MUTED).apply { setPadding(0, dp(8), 0, 0) }
        page.addView(workers)
        note = text("", 12f, MUTED)
        page.addView(note)
        status = text("", 12f, MUTED).apply { setPadding(0, dp(4), 0, 0) }
        page.addView(status)

        setContentView(root)
        applySystemInsets(root)
        bind()
    }

    override fun onStart() {
        super.onStart()
        store.registerListener(listener)
        bind()
    }

    override fun onStop() {
        store.unregisterListener(listener)
        super.onStop()
    }

    private fun bind() {
        val now = System.currentTimeMillis()
        val parsed = store.weeklyBody?.let { runCatching { parseWeekly(it) }.getOrNull() }
        val view = WeeklyDisplay.build(
            nowMs = now, zone = zone, configured = store.isConfigured, parsed = parsed,
            lastSuccessAtMs = store.weeklySuccessAtMs, lastError = store.weeklyError,
            staleAfterMs = Refresh.staleAfterMs(store.refreshMinutes),
        )
        used.text = view.usedText
        pace.text = view.paceText
        pace.setTextColor(when (view.overPace) { true -> BAD; false -> OK; null -> MUTED })
        reset.text = view.resetText
        legend.text = legendText(view)
        workers.text = WeeklyWidgetRenderer.workers(view)
        val estimated = parsed?.workers?.filterValues { it.estimate }?.keys.orEmpty()
        note.text = if (estimated.isEmpty()) "" else
            "~ estimate: fewer than 5 workers measured (${estimated.joinToString(", ") { it.replaceFirstChar(Char::uppercase) }})"
        note.visibility = if (estimated.isEmpty()) View.GONE else View.VISIBLE
        status.text = view.statusLine
        chart.set(parsed, now)
        bindDetail()
    }

    private fun bindDetail() {
        val u = chart.usage
        val i = chart.selected
        if (u == null) {
            detail.text = "No weekly data yet: the widget fills it on its next refresh."
            return
        }
        if (i == null || i !in u.days.indices) {
            detail.text = "Touch a day to see its value."
            return
        }
        val d = u.days[i]
        val date = DAY.format(Instant.ofEpochMilli(u.startMs + (u.resetMs - u.startMs) * i / u.days.size).atZone(zone))
        val sb = SpannableStringBuilder()
        if (d.state == "future") {
            sb.append("$date: not started yet")
            detail.text = sb
            return
        }
        val s = sb.length
        sb.append("$date: ${WeeklyDisplay.fmt1(d.deltaPercent)}% of the week")
        sb.setSpan(ForegroundColorSpan(INK), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val total = WeeklyDisplay.weekTotalAtDayEnd(u, i, chart.nowMs)
        sb.append(if (d.state == "partial") "  (today so far), week total now " else "  ·  week total at day end ")
        sb.append("${WeeklyDisplay.fmt1(total)}%\n")
        val order = u.types.map { it.id to it.label } + ("unattributed" to "Unattributed")
        var first = true
        for ((id, label) in order) {
            val v = d.byType[id] ?: 0.0
            if (v <= 0.0) continue
            if (!first) sb.append("   ")
            first = false
            val sq = sb.length
            sb.append("\u25A0 ")
            sb.setSpan(ForegroundColorSpan(WeeklyChart.colorOf(id)), sq, sq + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("$label ")
            val n = sb.length
            sb.append("${WeeklyDisplay.fmt1(v)}%")
            sb.setSpan(ForegroundColorSpan(INK), n, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (first) sb.append("no usage that day")
        detail.text = sb
    }

    private fun legendText(view: WeeklyView): CharSequence {
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
        sb.append("   week to date")
        return sb
    }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val BG = 0xFF111318.toInt()
        const val INK = 0xFFEEF2EF.toInt()
        const val MUTED = 0xFF8E9993.toInt()
        const val OK = 0xFF6FD3A0.toInt()
        const val BAD = 0xFFF2877F.toInt()
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    }
}

/** The chart, drawn by [WeeklyChart], with a day selected by touch. */
class WeeklyChartView(context: Context, private val onSelect: () -> Unit) : View(context) {
    var usage: WeeklyUsage? = null
        private set
    var nowMs: Long = 0L
        private set
    var selected: Int? = null
        private set
    private var geometry: WeeklyChart.Geometry? = null

    init {
        contentDescription = context.getString(R.string.weekly_chart_desc)
    }

    fun set(u: WeeklyUsage?, now: Long) {
        usage = u
        nowMs = now
        if (u == null || (selected ?: 0) !in u.days.indices) selected = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val u = usage ?: return
        geometry = WeeklyChart.draw(
            canvas, u, nowMs, width.toFloat(), height.toFloat(), resources.displayMetrics.density, selected, textScale = 1.3f,
        )
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is called on ACTION_UP
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val day = g.dayAt(event.x)
                if (day != null && day != selected) {
                    selected = day
                    invalidate()
                    onSelect()
                }
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
