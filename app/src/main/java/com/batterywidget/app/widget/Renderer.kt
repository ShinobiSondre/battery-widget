package com.batterywidget.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.batterywidget.app.data.Item
import com.batterywidget.app.data.WidgetStyle
import kotlin.math.ceil
import kotlin.math.min

/**
 * Draws the whole widget as one picture. Doing it this way (instead of an Android layout) lets
 * the rings, bars and icons look exactly the same in every launcher, at any size or transparency.
 */
object Renderer {
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val AMBER = 0xFFFBBF24.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val GRAY = 0xFF9CA3AF.toInt()
    private const val WHITE = Color.WHITE
    private const val SOFT = 0xFFE5E7EB.toInt()
    private val TRACK = Color.argb(60, 255, 255, 255)

    fun levelColor(i: Item) = when {
        i.level < 0 -> GRAY
        i.charging -> GREEN
        i.level <= 20 -> RED
        i.level <= 50 -> AMBER
        else -> GREEN
    }

    private fun pct(i: Item) = if (i.level < 0) "–" else "${i.level}%"

    /**
     * @param wDp, hDp the widget's size in dp
     * @param scale pixels per dp
     * @param opacity background, 0 (see-through) to 100 (solid)
     */
    fun render(items: List<Item>, style: WidgetStyle, opacity: Int, wDp: Float, hDp: Float, scale: Float): Bitmap {
        val w = (wDp * scale).toInt().coerceAtLeast(1)
        val h = (hDp * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = scale // 1 dp in pixels

        // Background "glass"
        val radius = 22 * d
        val box = RectF(0f, 0f, w.toFloat(), h.toFloat())
        if (opacity > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(opacity * 255 / 100, 16, 20, 28) }
            c.drawRoundRect(box, radius, radius, bg)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE; strokeWidth = d; color = Color.argb(46, 255, 255, 255)
            }
            c.drawRoundRect(RectF(d / 2, d / 2, w - d / 2, h - d / 2), radius, radius, border)
        }

        val ctx = Ctx(c, d, opacity < 40)
        val pad = 14 * d
        val area = RectF(pad, pad, w - pad, h - pad)
        when (style) {
            WidgetStyle.RINGS -> rings(ctx, items, area)
            WidgetStyle.BARS -> bars(ctx, items, area)
            WidgetStyle.BIG -> big(ctx, items, area)
        }
        return bmp
    }

    private class Ctx(val c: Canvas, val d: Float, val shadow: Boolean) {
        fun text(sizePx: Float, bold: Boolean = false, color: Int = WHITE) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            // Keeps text readable on bright wallpapers when the background is see-through.
            if (shadow) setShadowLayer(3 * d, 0f, d, Color.argb(150, 0, 0, 0))
        }
    }

    // ---- A · Rings ----
    private fun rings(x: Ctx, items: List<Item>, a: RectF) {
        val n = items.size
        var best = 0f; var bestCols = 1
        for (cols in 1..n) {
            val rows = ceil(n / cols.toFloat()).toInt()
            val cw = a.width() / cols
            val ch = a.height() / rows
            val label = (ch * 0.18f).coerceIn(9 * x.d, 14 * x.d)
            val diam = min(cw * 0.8f, ch - label * 1.5f)
            if (diam > best) { best = diam; bestCols = cols }
        }
        val cols = bestCols
        val rows = ceil(n / cols.toFloat()).toInt()
        val cw = a.width() / cols
        val ch = a.height() / rows
        val diam = best.coerceAtLeast(10 * x.d)
        val labelSize = (ch * 0.18f).coerceIn(9 * x.d, 14 * x.d)
        val stroke = diam * 0.09f
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = TRACK }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        val label = x.text(labelSize, bold = true)
        items.forEachIndexed { i, it ->
            val col = i % cols
            val row = i / cols
            // Centre a short last row
            val inRow = if (row == rows - 1) n - row * cols else cols
            val rowStart = a.left + (a.width() - inRow * cw) / 2f
            val cx = rowStart + col * cw + cw / 2f
            val blockH = diam + labelSize * 1.5f
            val top = a.top + row * ch + (ch - blockH) / 2f
            val cy = top + diam / 2f
            val r = diam / 2f - stroke / 2f
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            x.c.drawOval(oval, track)
            if (it.level > 0) {
                arc.color = levelColor(it)
                x.c.drawArc(oval, -90f, 360f * it.level / 100f, false, arc)
            }
            Icons.draw(x.c, it.icon, cx, cy, diam * 0.46f, WHITE)
            val t = pct(it)
            val tw = label.measureText(t)
            val boltW = if (it.charging) labelSize * 0.9f else 0f
            val tx = cx - (tw + boltW) / 2f
            val ty = top + diam + labelSize * 1.25f
            x.c.drawText(t, tx, ty, label)
            if (it.charging) Icons.bolt(x.c, tx + tw + boltW / 2f, ty - labelSize * 0.35f, labelSize * 0.9f, GREEN)
        }
    }

    // ---- B · Bars ----
    private fun bars(x: Ctx, all: List<Item>, a: RectF) {
        val d = x.d
        val maxRows = (a.height() / (18 * d)).toInt().coerceAtLeast(1)
        val items = all.take(maxRows)
        val rowH = min(a.height() / items.size, 36 * d)
        val top0 = a.top + (a.height() - rowH * items.size) / 2f
        val size = (rowH * 0.42f).coerceIn(9 * d, 15 * d)
        val icon = (rowH * 0.62f).coerceAtMost(22 * d)
        val name = x.text(size)
        val num = x.text(size, bold = true)
        val pctW = num.measureText("100%")
        val boltW = size * 0.9f
        val barH = (rowH * 0.2f).coerceIn(4 * d, 8 * d)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val nameW = (a.width() * 0.32f).coerceAtLeast(40 * d)
        items.forEachIndexed { i, it ->
            val cy = top0 + rowH * i + rowH / 2f
            var xPos = a.left
            Icons.draw(x.c, it.icon, xPos + icon / 2f, cy, icon, WHITE)
            xPos += icon + 8 * d
            val label = TextUtils.ellipsize(it.name, name, nameW, TextUtils.TruncateAt.END).toString()
            val base = cy - (name.descent() + name.ascent()) / 2f
            x.c.drawText(label, xPos, base, name)
            xPos += nameW + 8 * d
            val right = a.right
            val pctLeft = right - pctW - boltW
            val barRight = pctLeft - 8 * d
            if (barRight - xPos >= 20 * d) {
                val r = barH / 2f
                x.c.drawRoundRect(RectF(xPos, cy - r, barRight, cy + r), r, r, track)
                if (it.level > 0) {
                    fill.color = levelColor(it)
                    val end = xPos + (barRight - xPos) * it.level / 100f
                    x.c.drawRoundRect(RectF(xPos, cy - r, end.coerceAtLeast(xPos + barH), cy + r), r, r, fill)
                }
            }
            val t = pct(it)
            val tw = num.measureText(t)
            x.c.drawText(t, right - boltW - tw, base, num)
            if (it.charging) Icons.bolt(x.c, right - boltW / 2f, cy, boltW, GREEN)
        }
    }

    // ---- C · Big number ----
    private fun big(x: Ctx, items: List<Item>, a: RectF) {
        val d = x.d
        val low = items.filter { it.level >= 0 }.minByOrNull { it.level } ?: items.first()
        val others = items.filter { it !== low }
        val chipH = 24 * d
        val showChips = others.isNotEmpty() && a.height() > 110 * d
        val headH = if (showChips) a.height() - chipH - 10 * d else a.height()

        val small = x.text((headH * 0.13f).coerceIn(9 * d, 13 * d), color = SOFT)
        val numSize = min(headH * 0.48f, a.width() * 0.3f)
        val num = x.text(numSize, bold = true)
        val nameSize = (headH * 0.15f).coerceIn(10 * d, 16 * d)
        val name = x.text(nameSize)

        val blockH = small.textSize * 1.3f + numSize * 1.0f + nameSize * 1.5f
        var y = a.top + (headH - blockH) / 2f
        y += small.textSize
        x.c.drawText("Lowest", a.left, y, small)
        y += small.textSize * 0.3f + numSize * 0.9f
        val t = pct(low)
        num.color = WHITE
        x.c.drawText(t, a.left, y, num)
        if (low.charging) Icons.bolt(x.c, a.left + num.measureText(t) + numSize * 0.25f, y - numSize * 0.35f, numSize * 0.4f, GREEN)
        y += nameSize * 1.5f
        val icon = nameSize * 1.3f
        Icons.draw(x.c, low.icon, a.left + icon / 2f, y - nameSize * 0.35f, icon, levelColor(low))
        val label = TextUtils.ellipsize(low.name, name, a.width() - icon - 8 * d, TextUtils.TruncateAt.END).toString()
        x.c.drawText(label, a.left + icon + 8 * d, y, name)

        if (!showChips) return
        val chipText = x.text(12 * d, bold = true)
        val chipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 255, 255, 255) }
        var cx = a.left
        val cy = a.bottom - chipH
        for (it in others) {
            val tx = pct(it)
            val iw = 15 * d
            val cw = 10 * d + iw + 5 * d + chipText.measureText(tx) + 10 * d
            if (cx + cw > a.right) break
            x.c.drawRoundRect(RectF(cx, cy, cx + cw, cy + chipH), chipH / 2f, chipH / 2f, chipBg)
            Icons.draw(x.c, it.icon, cx + 10 * d + iw / 2f, cy + chipH / 2f, iw, levelColor(it))
            val base = cy + chipH / 2f - (chipText.descent() + chipText.ascent()) / 2f
            x.c.drawText(tx, cx + 10 * d + iw + 5 * d, base, chipText)
            cx += cw + 6 * d
        }
    }
}
