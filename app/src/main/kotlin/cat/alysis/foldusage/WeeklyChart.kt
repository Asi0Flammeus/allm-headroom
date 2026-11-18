package cat.alysis.foldusage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws the weekly chart into a Bitmap for the RemoteViews ImageView: one 0-100 %
 * axis shared by the cumulative curve, the dashed even-pace diagonal and the
 * stacked per-day bars. Window-relative: the x axis runs from the window start
 * to the reset the bridge reported, so no weekday is hardcoded.
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
    private const val INK = 0xFFF4F1E8.toInt()
    private const val HALO = 0xCC111318.toInt()
    private const val MUTED = 0xFF8E9993.toInt()
    private const val FAINT = 0xFF5B6560.toInt()
    private const val GRID = 0x1FFFFFFF
    private const val BASELINE = 0xFF7C878F.toInt()
    private const val OVER = 0x38F2877F
    private const val UNDER = 0x336FD3A0

    fun colorOf(typeId: String): Int = TYPE_COLORS[typeId] ?: UNATTRIBUTED

    /** [density] is px per dp; the bitmap is [widthPx] x [heightPx]. */
    fun render(u: WeeklyUsage, nowMs: Long, widthPx: Int, heightPx: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun dp(v: Float) = v * density

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = dp(10f); color = MUTED; typeface = Typeface.DEFAULT
        }
        val left = label.measureText("100") + dp(6f)
        val top = dp(6f)
        val bottom = heightPx - dp(16f)
        val right = widthPx - dp(4f)
        val plotW = right - left
        val plotH = bottom - top
        val span = (u.resetMs - u.startMs).coerceAtLeast(1L).toFloat()
        fun x(ms: Long) = left + plotW * ((ms - u.startMs) / span).coerceIn(0f, 1f)
        fun y(pct: Double) = bottom - plotH * (pct.coerceIn(0.0, 100.0) / 100.0).toFloat()

        // Grid and y labels.
        val grid = Paint().apply { color = GRID; strokeWidth = dp(1f) }
        label.textAlign = Paint.Align.RIGHT
        for (p in listOf(0, 25, 50, 75, 100)) {
            val yy = y(p.toDouble())
            c.drawLine(left, yy, right, yy, grid)
            c.drawText("$p", left - dp(5f), yy + dp(3.5f), label)
        }
        val days = u.days.size.coerceAtLeast(1)
        val slot = plotW / days
        for (i in 1 until days) c.drawLine(left + slot * i, top, left + slot * i, bottom, grid)

        // Stacked day bars, same % axis as the curve.
        val barW = slot * 0.46f
        val seg = Paint(Paint.ANTI_ALIAS_FLAG)
        val order = u.types.map { it.id } + "unattributed"
        u.days.forEachIndexed { i, d ->
            val cx = left + slot * (i + 0.5f)
            var acc = 0.0
            for (id in order) {
                val v = d.byType[id] ?: 0.0
                if (v <= 0.0) continue
                seg.color = colorOf(id)
                seg.alpha = if (d.state == "partial") 190 else 235
                c.drawRect(RectF(cx - barW / 2, y(acc + v), cx + barW / 2, y(acc)), seg)
                acc += v
            }
            label.textAlign = Paint.Align.CENTER
            label.color = if (d.state == "future") FAINT else MUTED
            c.drawText(d.label, cx, heightPx - dp(3f), label)
        }

        // Curve, clipped to the window and to "now".
        val pts = u.curve.filter { it.first in u.startMs..u.resetMs }.toMutableList()
        if (pts.isEmpty() || pts.first().first > u.startMs) pts.add(0, u.startMs to 0.0)
        val nowClamped = nowMs.coerceIn(u.startMs, u.resetMs)
        if (pts.last().first < nowClamped) pts.add(nowClamped to u.usedPercent)
        val curve = Path().apply {
            moveTo(x(pts[0].first), y(pts[0].second))
            for (p in pts.drop(1)) lineTo(x(p.first), y(p.second))
        }

        // Over / under shading between the curve and the even-pace diagonal.
        val between = Path(curve).apply {
            for (p in pts.asReversed()) lineTo(x(p.first), y(100.0 * (p.first - u.startMs) / span))
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
        c.drawCircle(nowX, y(u.usedPercent), dp(3.5f), dot)
        return bmp
    }
}
