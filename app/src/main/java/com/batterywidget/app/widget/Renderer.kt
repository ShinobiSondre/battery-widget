package com.batterywidget.app.widget

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
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
 *
 * Besides the normal picture it can draw a GLOW layer: a soft halo around anything charging
 * (orange), low (red) or nearing low (orange). The widget fades it in and out so they "breathe".
 */
object Renderer {
    private const val GREEN = 0xFF4ADE80.toInt()
    private const val AMBER = 0xFFFBBF24.toInt()
    private const val RED = 0xFFF87171.toInt()
    private const val ORANGE = 0xFFFB923C.toInt()
    private const val GRAY = 0xFF9CA3AF.toInt()
    private const val WHITE = Color.WHITE
    private const val SOFT = 0xFFE5E7EB.toInt()
    private val TRACK = Color.argb(60, 255, 255, 255)

    enum class Layer { NORMAL, GLOW }

    fun levelColor(i: Item) = when {
        i.level < 0 -> GRAY
        i.charging -> ORANGE
        i.level <= 20 -> RED
        i.level <= 50 -> AMBER
        else -> GREEN
    }

    private fun pct(i: Item) = if (i.level < 0) "–" else "${i.level}%"

    /**
     * @param wDp, hDp the widget's size in dp
     * @param scale pixels per dp
     * @param opacity background, 0 (see-through) to 100 (solid)
     * @param highlight names of devices that just connected; they get a soft spotlight
     */
    fun render(
        items: List<Item>, style: WidgetStyle, opacity: Int, wDp: Float, hDp: Float, scale: Float,
        layer: Layer = Layer.NORMAL, highlight: Set<String> = emptySet(), reserveCorner: Boolean = false
    ): Bitmap {
        val w = (wDp * scale).toInt().coerceAtLeast(1)
        val h = (hDp * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = scale // 1 dp in pixels

        if (layer == Layer.NORMAL && opacity > 0) {
            val radius = 22 * d
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(opacity * 255 / 100, 16, 20, 28) }
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, bg)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE; strokeWidth = d; color = Color.argb(46, 255, 255, 255)
            }
            c.drawRoundRect(RectF(d / 2, d / 2, w - d / 2, h - d / 2), radius, radius, border)
        }

        val x = Ctx(c, d, opacity < 40, layer == Layer.GLOW, highlight)
        val pad = 14 * d
        // Keep the top-right corner free for the connect button when it's showing.
        val area = RectF(pad, pad, w - pad - (if (reserveCorner) 40 * d else 0f), h - pad)
        when (style) {
            WidgetStyle.RINGS -> rings(x, items, area)
            WidgetStyle.BARS -> bars(x, items, area)
            WidgetStyle.BIG -> big(x, items, area)
        }
        return bmp
    }

    /**
     * The colour an item pulses in, or null when it shouldn't pulse:
     * orange while charging, red when low (20% or less), orange when nearing low (35% or less).
     */
    fun glowColor(i: Item): Int? = when {
        i.charging -> ORANGE
        i.level in 0..20 -> RED
        i.level in 21..35 -> ORANGE
        else -> null
    }

    fun anyGlow(items: List<Item>) = items.any { glowColor(it) != null }

    private class Ctx(val c: Canvas, val d: Float, val shadow: Boolean, val glow: Boolean, val highlight: Set<String>) {
        fun text(sizePx: Float, bold: Boolean = false, color: Int = WHITE) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            // Keeps text readable on bright wallpapers when the background is see-through.
            if (shadow) setShadowLayer(3 * d, 0f, d, Color.argb(150, 0, 0, 0))
        }

        /** Paint for the soft halo of the glow layer. */
        fun halo(width: Float, tint: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(210, Color.red(tint), Color.green(tint), Color.blue(tint))
            maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
        }

        /** Soft white spotlight behind a device that just connected. */
        fun spotlight(r: RectF, name: String) {
            if (glow || name !in highlight) return
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(70, 255, 255, 255)
                maskFilter = BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(r, 14 * d, 14 * d, p)
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
            if (x.glow) {
                val g = glowColor(it) ?: return@forEachIndexed
                x.c.drawOval(oval, x.halo(stroke * 1.8f, g))
                Icons.draw(x.c, it.icon, cx, cy, diam * 0.46f, g)
                return@forEachIndexed
            }
            x.spotlight(RectF(cx - cw / 2f + 2 * x.d, top - 4 * x.d, cx + cw / 2f - 2 * x.d, top + blockH + 2 * x.d), it.name)
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
            if (it.charging) Icons.bolt(x.c, tx + tw + boltW / 2f, ty - labelSize * 0.35f, labelSize * 0.9f, ORANGE)
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
            val iconX = xPos + icon / 2f
            xPos += icon + 8 * d
            val nameX = xPos
            xPos += nameW + 8 * d
            val right = a.right
            val pctLeft = right - pctW - boltW
            val barRight = pctLeft - 8 * d
            val barLeft = xPos
            val hasBar = barRight - barLeft >= 20 * d
            val r = barH / 2f

            if (x.glow) {
                val g = glowColor(it) ?: return@forEachIndexed
                if (hasBar && it.level > 0) {
                    val end = (barLeft + (barRight - barLeft) * it.level / 100f).coerceAtLeast(barLeft + barH)
                    x.c.drawLine(barLeft + r, cy, end - r, cy, x.halo(barH * 1.6f, g))
                }
                Icons.draw(x.c, it.icon, iconX, cy, icon, g)
                if (it.charging) Icons.bolt(x.c, right - boltW / 2f, cy, boltW * 1.15f, g)
                return@forEachIndexed
            }

            x.spotlight(RectF(a.left - 6 * d, cy - rowH / 2f + d, a.right + 6 * d, cy + rowH / 2f - d), it.name)
            Icons.draw(x.c, it.icon, iconX, cy, icon, WHITE)
            val label = TextUtils.ellipsize(it.name, name, nameW, TextUtils.TruncateAt.END).toString()
            val base = cy - (name.descent() + name.ascent()) / 2f
            x.c.drawText(label, nameX, base, name)
            if (hasBar) {
                x.c.drawRoundRect(RectF(barLeft, cy - r, barRight, cy + r), r, r, track)
                if (it.level > 0) {
                    fill.color = levelColor(it)
                    val end = barLeft + (barRight - barLeft) * it.level / 100f
                    x.c.drawRoundRect(RectF(barLeft, cy - r, end.coerceAtLeast(barLeft + barH), cy + r), r, r, fill)
                }
            }
            val t = pct(it)
            val tw = num.measureText(t)
            x.c.drawText(t, right - boltW - tw, base, num)
            if (it.charging) Icons.bolt(x.c, right - boltW / 2f, cy, boltW, ORANGE)
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
        val blockTop = y
        y += small.textSize
        val lowestY = y
        y += small.textSize * 0.3f + numSize * 0.9f
        val numY = y
        y += nameSize * 1.5f
        val nameY = y
        val t = pct(low)
        val icon = nameSize * 1.3f

        if (x.glow) {
            val gc = glowColor(low)
            if (gc != null) {
                val g = x.text(numSize, bold = true, color = gc).apply {
                    clearShadowLayer(); maskFilter = BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL)
                }
                x.c.drawText(t, a.left, numY, g)
                if (low.charging) Icons.bolt(x.c, a.left + num.measureText(t) + numSize * 0.25f, numY - numSize * 0.35f, numSize * 0.48f, gc)
            }
        } else {
            x.spotlight(RectF(a.left - 6 * d, blockTop - 4 * d, a.right, blockTop + blockH + 4 * d), low.name)
            x.c.drawText("Lowest", a.left, lowestY, small)
            x.c.drawText(t, a.left, numY, num)
            if (low.charging) Icons.bolt(x.c, a.left + num.measureText(t) + numSize * 0.25f, numY - numSize * 0.35f, numSize * 0.4f, ORANGE)
            Icons.draw(x.c, low.icon, a.left + icon / 2f, nameY - nameSize * 0.35f, icon, levelColor(low))
            val label = TextUtils.ellipsize(low.name, name, a.width() - icon - 8 * d, TextUtils.TruncateAt.END).toString()
            x.c.drawText(label, a.left + icon + 8 * d, nameY, name)
        }

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
            val chip = RectF(cx, cy, cx + cw, cy + chipH)
            if (x.glow) {
                val g = glowColor(it)
                if (g != null) {
                    x.c.drawRoundRect(chip, chipH / 2f, chipH / 2f, x.halo(2.5f * d, g))
                    Icons.draw(x.c, it.icon, cx + 10 * d + iw / 2f, cy + chipH / 2f, iw, g)
                }
            } else {
                x.spotlight(RectF(chip.left - 3 * d, chip.top - 3 * d, chip.right + 3 * d, chip.bottom + 3 * d), it.name)
                x.c.drawRoundRect(chip, chipH / 2f, chipH / 2f, chipBg)
                Icons.draw(x.c, it.icon, cx + 10 * d + iw / 2f, cy + chipH / 2f, iw, levelColor(it))
                val base = cy + chipH / 2f - (chipText.descent() + chipText.ascent()) / 2f
                x.c.drawText(tx, cx + 10 * d + iw + 5 * d, base, chipText)
            }
            cx += cw + 6 * d
        }
    }
}
