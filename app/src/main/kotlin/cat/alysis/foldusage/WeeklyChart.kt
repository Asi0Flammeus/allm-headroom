package cat.alysis.foldusage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws the weekly chart, for the widget's RemoteViews bitmap and for the full-screen
 * [WeeklyChartView]. Two y axes, each tied to its series by colour:
 *  - LEFT, 0-25 %: the stacked per-day bars (share of the weekly quota used that day).
 *    Its spine is a strip of the usage-type colours, its labels warm sand. A day above
 *    25 % stays pinned at the top with an overflow mark and its real value.
 *  - RIGHT, 0-100 %: the cumulative weekly curve, the dashed even-pace diagonal and the
 *    over/under shading. Spine and labels in the curve's ink.
 * Window-relative: the x axis runs from the window start to the reset the bridge
 * reported, so no weekday is hardcoded.
 */
object WeeklyChart {

    val TYPE_COLORS = mapOf(
        "main" to 0xFF8DB7FF.toInt(),
        "workers" to 0xFFF0A35E.toInt(),
        "sentinels" to 0xFF5FC9B0.toInt(),
        "t3" to 0xFFC79BF2.toInt(),
        "alfred" to 0xFFE3D27C.toInt(),
    )
    private const val UNATTRIBUTED = 0xFF5B636B.toInt()
    const val INK = 0xFFF4F1E8.toInt()
    const val BAR_AXIS = 0xFFE6BE8A.toInt() // left axis: day bars
    private const val HALO = 0xCC111318.toInt()
    private const val MUTED = 0xFF8E9993.toInt()
    private const val FAINT = 0xFF5B6560.toInt()
    private const val GRID = 0x1FFFFFFF
    private const val BASELINE = 0xFF7C878F.toInt()
    private const val OVER = 0x38F2877F
    private const val UNDER = 0x336FD3A0
    private const val SELECTED = 0x22FFFFFF
    private const val OVERFLOW = 0xFFF2877F.toInt()

    /** Left (bars) axis maximum, in % of the weekly quota per day. */
    const val BAR_MAX = 25.0
    private val BAR_TICKS = listOf(0, 5, 10, 15, 20, 25)
    private val WEEK_TICKS = listOf(0, 25, 50, 75, 100)

    fun colorOf(typeId: String): Int = TYPE_COLORS[typeId] ?: UNATTRIBUTED

    /** Where the plot sat in the last draw: the full-screen view maps a touch x to a day. */
    data class Geometry(val left: Float, val right: Float, val top: Float, val bottom: Float, val days: Int) {
        fun dayAt(x: Float): Int? {
            if (days == 0 || x < left || x > right) return null
            return ((x - left) / ((right - left) / days)).toInt().coerceIn(0, days - 1)
        }
    }

    /** The widget bitmap. [density] is px per dp; the bitmap is [widthPx] x [heightPx]. */
    fun render(u: WeeklyUsage, nowMs: Long, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), u, nowMs, bmp.width.toFloat(), bmp.height.toFloat(), density, null)
        return bmp
    }

    fun draw(
        c: Canvas, u: WeeklyUsage, nowMs: Long, widthPx: Float, heightPx: Float, density: Float,
        selected: Int?, textScale: Float = 1f,
    ): Geometry {
        fun dp(v: Float) = v * density

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(10f * textScale); color = MUTED; typeface = Typeface.DEFAULT
        }
        val title = Paint(label).apply { textSize = dp(9.5f * textScale); typeface = Typeface.DEFAULT_BOLD }
        val left = label.measureText("25%") + dp(9f)
        val right = widthPx - label.measureText("100%") - dp(9f)
        val top = title.textSize + dp(9f)
        val bottom = heightPx - label.textSize - dp(7f)
        val plotW = right - left
        val plotH = bottom - top
        val span = (u.resetMs - u.startMs).coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = left + plotW * ((ms - u.startMs) / span).coerceIn(0f, 1f)
        fun yWeek(pct: Double) = bottom - plotH * (pct.coerceIn(0.0, 100.0) / 100.0).toFloat()
        fun yBar(pct: Double) = bottom - plotH * (pct.coerceIn(0.0, BAR_MAX) / BAR_MAX).toFloat()

        val days = u.days.size.coerceAtLeast(1)
        val slot = plotW / days
        if (selected != null && selected in u.days.indices) {
            c.drawRect(left + slot * selected, top, left + slot * (selected + 1), bottom, Paint().apply { color = SELECTED })
        }

        // Grid on the right (week) ticks; the left axis has its own tick marks.
        val grid = Paint().apply { color = GRID; strokeWidth = dp(1f) }
        for (p in WEEK_TICKS) c.drawLine(left, yWeek(p.toDouble()), right, yWeek(p.toDouble()), grid)
        for (i in 1 until days) c.drawLine(left + slot * i, top, left + slot * i, bottom, grid)

        // Left axis: a spine striped in the usage-type colours, sand labels.
        val order = u.types.map { it.id } + "unattributed"
        val stripe = Paint()
        val spineW = dp(3f)
        order.dropLast(1).forEachIndexed { i, id ->
            stripe.color = colorOf(id)
            val n = (order.size - 1).coerceAtLeast(1)
            c.drawRect(left - spineW, bottom - plotH * (i + 1) / n, left, bottom - plotH * i / n, stripe)
        }
        label.textAlign = Paint.Align.RIGHT
        label.color = BAR_AXIS
        val tick = Paint().apply { color = BAR_AXIS; strokeWidth = dp(1f) }
        for (p in BAR_TICKS) {
            val yy = yBar(p.toDouble())
            c.drawLine(left - spineW - dp(3f), yy, left - spineW, yy, tick)
            c.drawText("$p%", left - spineW - dp(4f), yy + label.textSize * 0.35f, label)
        }
        title.color = BAR_AXIS
        title.textAlign = Paint.Align.LEFT
        c.drawText("per day", 0f, title.textSize, title)

        // Right axis: ink spine and labels, like the curve.
        val inkSpine = Paint().apply { color = INK; strokeWidth = dp(1.5f) }
        c.drawLine(right, top, right, bottom, inkSpine)
        label.textAlign = Paint.Align.LEFT
        label.color = INK
        for (p in WEEK_TICKS) {
            val yy = yWeek(p.toDouble())
            c.drawLine(right, yy, right + dp(3f), yy, inkSpine)
            c.drawText("$p%", right + dp(5f), yy + label.textSize * 0.35f, label)
        }
        title.color = INK
        title.textAlign = Paint.Align.RIGHT
        c.drawText("week total", widthPx, title.textSize, title)

        // Stacked day bars on the left axis.
        val barW = slot * 0.5f
        val seg = Paint(Paint.ANTI_ALIAS_FLAG)
        val valueText = Paint(label).apply {
            textAlign = Paint.Align.CENTER; color = INK; typeface = Typeface.DEFAULT_BOLD
        }
        val halo = Paint(valueText).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = HALO }
        u.days.forEachIndexed { i, d ->
            val cx = left + slot * (i + 0.5f)
            var acc = 0.0
            for (id in order) {
                val v = d.byType[id] ?: 0.0
                if (v <= 0.0 || acc >= BAR_MAX) { acc += v.coerceAtLeast(0.0); continue }
                seg.color = colorOf(id)
                seg.alpha = if (d.state == "partial") 190 else 235
                c.drawRect(RectF(cx - barW / 2, yBar(acc + v), cx + barW / 2, yBar(acc)), seg)
                acc += v
            }
            if (d.deltaPercent > BAR_MAX) {
                // Overflow: the bar is pinned at the top; a red cap, a notch and the real value say so.
                val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = OVERFLOW }
                c.drawRect(cx - barW / 2, top, cx + barW / 2, top + dp(2.5f), cap)
                val notch = Path().apply {
                    moveTo(cx - dp(5f), top + dp(9f)); lineTo(cx, top + dp(4f)); lineTo(cx + dp(5f), top + dp(9f))
                }
                c.drawPath(notch, Paint(cap).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f) })
                val t = "${WeeklyDisplay.fmt1(d.deltaPercent)}%"
                val ty = top + dp(9f) + valueText.textSize
                c.drawText(t, cx, ty, halo)
                c.drawText(t, cx, ty, valueText)
            } else if (i == selected && d.state != "future") {
                val t = "${WeeklyDisplay.fmt1(d.deltaPercent)}%"
                val ty = (yBar(d.deltaPercent) - dp(4f)).coerceAtLeast(top + valueText.textSize)
                c.drawText(t, cx, ty, halo)
                c.drawText(t, cx, ty, valueText)
            }
            label.textAlign = Paint.Align.CENTER
            label.color = when {
                i == selected -> INK
                d.state == "future" -> FAINT
                else -> MUTED
            }
            c.drawText(d.label, cx, heightPx - dp(3f), label)
        }

        // Curve on the right axis, clipped to the window and to "now".
        val pts = u.curve.filter { it.first in u.startMs..u.resetMs }.toMutableList()
        if (pts.isEmpty() || pts.first().first > u.startMs) pts.add(0, u.startMs to 0.0)
        val nowClamped = nowMs.coerceIn(u.startMs, u.resetMs)
        if (pts.last().first < nowClamped) pts.add(nowClamped to u.usedPercent)
        val curve = Path().apply {
            moveTo(x(pts[0].first), yWeek(pts[0].second))
            for (p in pts.drop(1)) lineTo(x(p.first), yWeek(p.second))
        }

        // Over / under shading between the curve and the even-pace diagonal.
        val between = Path(curve).apply {
            for (p in pts.asReversed()) lineTo(x(p.first), yWeek(100.0 * (p.first - u.startMs) / span))
            close()
        }
        val above = Path().apply { moveTo(left, bottom); lineTo(right, top); lineTo(left, top); close() }
        val below = Path().apply { moveTo(left, bottom); lineTo(right, top); lineTo(right, bottom); close() }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        c.save(); c.clipPath(above); fill.color = OVER; c.drawPath(between, fill); c.restore()
        c.save(); c.clipPath(below); fill.color = UNDER; c.drawPath(between, fill); c.restore()

        // Dashed even-pace baseline: 0 % at window start, 100 % at reset.
        val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = BASELINE; strokeWidth = dp(1.2f)
            pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
        }
        c.drawLine(left, bottom, right, top, dash)

        // "Now" guide.
        val nowX = x(nowClamped)
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = FAINT; strokeWidth = dp(1f)
            pathEffect = DashPathEffect(floatArrayOf(dp(1.5f), dp(3f)), 0f)
        }
        c.drawLine(nowX, top, nowX, bottom, guide)

        // Cumulative curve with a dark halo so it reads over the bars.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        line.color = HALO; line.strokeWidth = dp(4.5f); c.drawPath(curve, line)
        line.color = INK; line.strokeWidth = dp(2f); c.drawPath(curve, line)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK }
        c.drawCircle(nowX, yWeek(u.usedPercent), dp(3.5f), dot)

        if (selected != null && selected in u.days.indices && u.days[selected].state != "future") {
            val sx = x(WeeklyDisplay.dayEndMs(u, selected, nowMs))
            val sy = yWeek(WeeklyDisplay.weekTotalAtDayEnd(u, selected, nowMs))
            c.drawCircle(sx, sy, dp(5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HALO })
            c.drawCircle(sx, sy, dp(3.5f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
        }
        return Geometry(left, right, top, bottom, u.days.size)
    }
}
