package cat.alysis.foldusage

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 36) }
        val scroll = ScrollView(this).apply { addView(page) }
        setContentView(scroll)
        val store = UsageStore(this)
        title(page, "ALLM Headroom", 29, Color.rgb(24, 32, 29))
        text(page, "Your private AI capacity monitor", 15, Color.DKGRAY)
        val parsed = store.lastBody?.let { runCatching { parseUsage(it) }.getOrNull() }
        if (parsed == null) { text(page, "Connect your private relay to see capacity and history.", 16, Color.DKGRAY); return }
        title(page, "Capacity now", 20, Color.rgb(24, 32, 29))
        DisplayState.buildRows(parsed, System.currentTimeMillis(), ZoneId.systemDefault()).forEach { row ->
            if (row.remainingPercent != null) capacityRow(page, row) else text(page, "${row.provider} · ${row.window}: no data", 14, Color.GRAY)
        }
        title(page, "Capacity history", 20, Color.rgb(24, 32, 29))
        val history = store.capacityHistory()
        if (history.isEmpty()) text(page, "History begins with the next successful refresh.", 14, Color.GRAY)
        else history.groupBy { it.day }.toSortedMap().forEach { (day, points) ->
            text(page, day, 14, Color.DKGRAY)
            points.forEach { p -> capacityRow(page, Row(p.provider, false, p.window, p.remaining, null, "")) }
        }
        title(page, "Model mix", 20, Color.rgb(24, 32, 29))
        text(page, "Token-by-model activity is not supplied by the subscription APIs yet. When the private relay receives model-attributed events, this section will show daily stacked bars: Anthropic in orange, OpenAI in green, with deeper tones for higher-tier models.", 14, Color.DKGRAY)
    }

    private fun capacityRow(parent: LinearLayout, row: Row) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 8) }
        val label = TextView(this).apply { text = "${row.provider.replaceFirstChar { it.uppercase() }} · ${row.window}"; textSize = 13f; setTextColor(Color.DKGRAY) }
        line.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = row.remainingPercent ?: 0; progressTintList = android.content.res.ColorStateList.valueOf(headroomColor(row.remainingPercent ?: 0)) }
        line.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        val pct = TextView(this).apply { text = "  ${row.remainingPercent}%"; textSize = 13f; setTextColor(headroomColor(row.remainingPercent ?: 0)) }
        line.addView(pct)
        parent.addView(line)
    }
    private fun headroomColor(value: Int): Int = when { value > 30 -> Color.rgb(55, 142, 101); value > 20 -> Color.rgb(245, 158, 11); value > 10 -> Color.rgb(234, 110, 32); value > 5 -> Color.rgb(220, 70, 55); else -> Color.rgb(185, 37, 37) }
    private fun title(parent: LinearLayout, value: String, size: Int, color: Int) = text(parent, value, size, color).apply { setPadding(0, 24, 0, 8) }
    private fun text(parent: LinearLayout, value: String, size: Int, color: Int): TextView = TextView(this).also { it.text = value; it.textSize = size.toFloat(); it.setTextColor(color); parent.addView(it) }
}
