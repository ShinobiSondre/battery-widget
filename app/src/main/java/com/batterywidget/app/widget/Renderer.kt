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
        layer: Layer = Layer.NORMAL, highlight: Set<String> = emptySet(), frame: Int = 0,
        reserveCorner: Boolean = false,
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

        val x = Ctx(c, d, opacity < 40, layer == Layer.GLOW, highlight, frame)
        val pad = 14 * d
        val area = RectF(pad, pad, w - pad, h - pad)
        // Keep clear of the connect button in the top-right corner (34 dp + 8 dp margin):
        // on wide widgets give up a strip on the right, on tall ones a strip at the top.
        if (reserveCorner) {
            val clear = 46 * d
            if (wDp >= hDp * 1.2f) area.right = minOf(area.right, w - clear)
            else area.top = maxOf(area.top, clear)
        }
        when (style) {
            WidgetStyle.RINGS -> rings(x, items, area)
            WidgetStyle.BARS -> bars(x, items, area)
            WidgetStyle.BIG -> big(x, items, area)
        }
        return bmp
    }

    /** The three animations. */
    enum class Fx { SHINE, RIPPLE, BREATHE }

    /**
     * Which animations are on. The first three play automatically when they matter;
     * the last three play all the time on every device, in the device's own colour.
     */
    data class Anims(
        val charge: Boolean = true,   // gold shine while charging
        val low: Boolean = true,      // red ripple at 20% or less
        val near: Boolean = true,     // orange breathing at 21-35%
        val shine: Boolean = false,   // always: shine
        val ripple: Boolean = false,  // always: ripple
        val breathe: Boolean = false, // always: breathing
    )

    @Volatile var anims = Anims()

    /** The animations an item plays right now, each with the colour it plays in. */
    fun effects(i: Item, a: Anims = anims): Map<Fx, Int> {
        if (i.level < 0 && !i.charging) return emptyMap()
        val out = LinkedHashMap<Fx, Int>()
        when {
            i.charging -> if (a.charge) out[Fx.SHINE] = ORANGE
            i.level <= 20 -> if (a.low) out[Fx.RIPPLE] = RED
            i.level <= 35 -> if (a.near) out[Fx.BREATHE] = ORANGE
        }
        val own = levelColor(i)
        if (a.shine) out.putIfAbsent(Fx.SHINE, own)
        if (a.ripple) out.putIfAbsent(Fx.RIPPLE, own)
        if (a.breathe) out.putIfAbsent(Fx.BREATHE, own)
        return out
    }

    /** The colour an item pulses in, or null when it doesn't animate. */
    fun glowColor(i: Item, a: Anims = anims): Int? = effects(i, a).values.firstOrNull()

    fun anyGlow(items: List<Item>) = items.any { effects(it).isNotEmpty() }

    /** The bright highlight colour for a shine: gold while charging, otherwise a light tint of the colour. */
    private fun shineTint(i: Item, g: Int): Int {
        if (i.charging) return SHINE
        fun lift(v: Int) = (v + (255 - v) * 0.65f).toInt()
        return Color.rgb(lift(Color.red(g)), lift(Color.green(g)), lift(Color.blue(g)))
    }

    /** The glow is drawn as this many frames; the widget flips through them [FRAME_MS] apart. */
    const val GLOW_FRAMES = 30
    const val FRAME_MS = 40

    private const val SHINE = 0xFFFDE68A.toInt()

    /** Where we are in the loop, 0..1. */
    private fun phase(frame: Int) = frame / GLOW_FRAMES.toFloat()

    /** Two ripples per loop; how far along ripple k is (0..1). */
    private fun ripple(frame: Int, k: Int = 0) = (phase(frame) * 2 + k * 0.5f) % 1f

    /** Breathing brightness, 0..1. */
    private fun breathe(frame: Int) = 0.5f - 0.5f * kotlin.math.cos(2 * Math.PI * phase(frame)).toFloat()

    /** How bright a simple glow (big number, chips) is for an effect in a frame, 0..1. */
    private fun pulse(fx: Fx, frame: Int): Float = when (fx) {
        Fx.SHINE -> 0.5f + 0.5f * kotlin.math.sin(2 * Math.PI * phase(frame)).toFloat()
        Fx.RIPPLE -> 0.25f + 0.75f * (1 - ripple(frame)).let { it * it }
        Fx.BREATHE -> 0.1f + 0.9f * breathe(frame)
    }

    /** All the animations of one ring, drawn on the glow layer. */
    private fun ringGlow(x: Ctx, it: Item, cx: Float, cy: Float, r: Float, stroke: Float) {
        val sweep = 360f * it.level.coerceIn(0, 100) / 100f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        val soft = { w: Float, blur: Float, col: Int -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; color = col
            if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        } }
        for ((fx, g) in effects(it)) when (fx) {
            Fx.SHINE -> {
                if (sweep <= 0f) continue
                // A bright highlight runs from the start of the filled part to its end, then again.
                val end = -90f + sweep
                val half = (sweep * 0.14f).coerceIn(14f, 34f)
                val head = -90f - half + (sweep + 2 * half) * phase(x.frame)
                fun seg(h: Float, p: Paint) {
                    val from = maxOf(-90f, head - h); val to = minOf(end, head + h)
                    if (to > from) x.c.drawArc(oval, from, to - from, false, p)
                }
                val tint = shineTint(it, g)
                seg(half, soft(stroke * 1.6f, 4 * x.d, withAlpha(g, 0.55f)))
                seg(half * 0.75f, soft(stroke, 1.5f * x.d, withAlpha(tint, 1f)))
                seg(half * 0.3f, soft(stroke * 0.45f, x.d, withAlpha(WHITE, 0.95f)))
            }
            Fx.RIPPLE -> for (k in 0..1) {
                // Rings that grow out of the ring and fade.
                val q = ripple(x.frame, k)
                val p = soft(maxOf(1.2f * x.d, stroke * 0.35f * (1 - q)), 1.5f * x.d, withAlpha(g, 0.9f * (1 - q)))
                x.c.drawCircle(cx, cy, r + stroke * 0.5f + q * stroke * 2.4f, p)
            }
            Fx.BREATHE -> if (sweep > 0f) {
                val b = breathe(x.frame)
                x.c.drawArc(oval, -90f, sweep, false, soft(stroke * (1.2f + 0.8f * b), (3 + 5 * b) * x.d, withAlpha(g, 0.15f + 0.75f * b)))
            }
        }
    }

    /** All the animations of one bar, drawn on the glow layer. */
    private fun barGlow(x: Ctx, it: Item, barLeft: Float, barRight: Float, cy: Float, barH: Float) {
        val d = x.d
        val r = barH / 2f
        val end = (barLeft + (barRight - barLeft) * it.level.coerceIn(0, 100) / 100f).coerceAtLeast(barLeft + barH)
        val x0 = barLeft + r; val x1 = end - r
        val soft = { w: Float, blur: Float, col: Int -> Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; color = col
            if (blur > 0) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        } }
        for ((fx, g) in effects(it)) when (fx) {
            Fx.SHINE -> {
                val len = x1 - x0
                val hw = maxOf(len * 0.14f, 8 * d)
                val head = x0 - hw + (len + 2 * hw) * phase(x.frame)
                fun seg(h: Float, p: Paint) {
                    val a0 = maxOf(x0, head - h); val a1 = minOf(x1, head + h)
                    if (a1 > a0) x.c.drawLine(a0, cy, a1, cy, p)
                }
                seg(hw, soft(barH * 1.6f, 3 * d, withAlpha(g, 0.55f)))
                seg(hw * 0.75f, soft(barH, d, withAlpha(shineTint(it, g), 1f)))
                seg(hw * 0.3f, soft(barH * 0.45f, 0.8f * d, withAlpha(WHITE, 0.95f)))
            }
            Fx.RIPPLE -> for (k in 0..1) {
                val q = ripple(x.frame, k)
                val grow = barH * 0.3f + q * barH * 1.8f
                val box = RectF(barLeft - grow, cy - r - grow, end + grow, cy + r + grow)
                val p = soft(maxOf(d, barH * 0.4f * (1 - q)), d, withAlpha(g, 0.9f * (1 - q)))
                x.c.drawRoundRect(box, r + grow, r + grow, p)
            }
            Fx.BREATHE -> {
                val b = breathe(x.frame)
                x.c.drawLine(x0, cy, x1, cy, soft(barH * (1.2f + 0.8f * b), (2 + 4 * b) * d, withAlpha(g, 0.15f + 0.75f * b)))
            }
        }
    }

    private fun withAlpha(tint: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(tint), Color.green(tint), Color.blue(tint))

    private class Ctx(
        val c: Canvas, val d: Float, val shadow: Boolean, val glow: Boolean, val highlight: Set<String>,
        val frame: Int = 0,
    ) {
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
                ringGlow(x, it, cx, cy, r, stroke)
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
                if (hasBar) barGlow(x, it, barLeft, barRight, cy, barH)
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
            effects(low).entries.firstOrNull()?.let { (fx, gc) ->
                val g = x.text(numSize, bold = true, color = gc).apply {
                    clearShadowLayer(); maskFilter = BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL)
                }
                g.alpha = (255 * pulse(fx, x.frame)).toInt()
                x.c.drawText(t, a.left, numY, g)
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
                effects(it).entries.firstOrNull()?.let { (fx, g) ->
                    x.c.drawRoundRect(chip, chipH / 2f, chipH / 2f, x.halo(2.5f * d, g).apply { color = withAlpha(g, 0.9f * pulse(fx, x.frame)) })
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
