package cat.alysis.foldusage

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.time.ZoneId

/** The explanatory surface opened by the home-screen Headroom panel. */
class HeadroomActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paper = Color.rgb(250, 248, 243)
        window.statusBarColor = paper
        window.navigationBarColor = paper
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 36); setBackgroundColor(paper) }
        val scroll = ScrollView(this).apply { setBackgroundColor(paper); addView(page) }
        setContentView(scroll)
        val store = UsageStore(this)
        title(page, "ALLM Headroom", 29, INK)
        text(page, "Your private AI capacity monitor", 15, BODY)
        val parsed = store.lastBody?.let { runCatching { parseUsage(it) }.getOrNull() }
        if (parsed == null) { text(page, "Connect your private relay to see capacity and history.", 16, BODY); return }
        title(page, "Capacity now", 20, INK)
        DisplayState.buildRows(parsed, System.currentTimeMillis(), ZoneId.systemDefault()).forEach { row ->
            if (row.remainingPercent != null) capacityRow(page, row) else text(page, "${row.provider} · ${row.window}: no data", 14, MUTED)
        }
        title(page, "Capacity history", 20, INK)
        val history = store.capacityHistory()
        if (history.isEmpty()) text(page, "History begins with the next successful refresh.", 14, MUTED)
        else history.groupBy { it.day }.toSortedMap().forEach { (day, points) ->
            text(page, day, 14, BODY)
            points.forEach { p -> capacityRow(page, Row(p.provider, false, p.window, p.remaining, null, "")) }
        }
        title(page, "Model mix", 20, INK)
        text(page, "Subscription APIs expose capacity windows, not per-model token history. Headroom can collect exact local CLI-session totals from each paired computer; this panel will show them once those private collectors are connected.", 14, BODY)
    }

    private fun capacityRow(parent: LinearLayout, row: Row) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
        val label = TextView(this).apply { text = "${row.provider.replaceFirstChar { it.uppercase() }} · ${row.window}"; textSize = 13f; setTextColor(BODY) }
        line.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = row.remainingPercent ?: 0; progressTintList = android.content.res.ColorStateList.valueOf(headroomColor(row.remainingPercent ?: 0)) }
        line.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        val pct = TextView(this).apply { text = "  ${row.remainingPercent}%"; textSize = 13f; setTextColor(headroomColor(row.remainingPercent ?: 0)) }
        line.addView(pct)
        parent.addView(line)
    }
    private fun headroomColor(value: Int): Int = when { value > 30 -> Color.rgb(22, 100, 65); value > 20 -> Color.rgb(142, 76, 0); value > 10 -> Color.rgb(155, 58, 12); value > 5 -> Color.rgb(164, 45, 38); else -> Color.rgb(132, 29, 29) }
    private fun title(parent: LinearLayout, value: String, size: Int, color: Int) = text(parent, value, size, color).apply { setPadding(0, 24, 0, 8) }
    private fun text(parent: LinearLayout, value: String, size: Int, color: Int): TextView = TextView(this).also { it.text = value; it.textSize = size.toFloat(); it.setTextColor(color); parent.addView(it) }

    private companion object {
        const val INK = 0xFF16201C.toInt()
        const val BODY = 0xFF33413B.toInt()
        const val MUTED = 0xFF59655F.toInt()
    }
}
