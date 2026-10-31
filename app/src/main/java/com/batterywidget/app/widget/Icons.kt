package com.batterywidget.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.PathParser
import com.batterywidget.app.data.IconKind

/** Device icons, drawn from 24×24 path data so they stay sharp at any size. */
object Icons {
    private const val L_TIP = "M3.8 5.2a3.2 3.2 0 0 0 0 5.6a5 5 0 0 1 0-5.6z "
    private const val L_HEAD = "M4.4 8a3.4 3.2 0 1 0 6.8 0a3.4 3.2 0 1 0-6.8 0z "
    private const val L_STEM = "M8.9 8.5h2.2v10.3a1.1 1.1 0 0 1-2.2 0z "
    private const val R_HEAD = "M12.8 8a3.4 3.2 0 1 0 6.8 0a3.4 3.2 0 1 0-6.8 0z "
    private const val R_STEM = "M12.9 8.5h2.2v10.3a1.1 1.1 0 0 1-2.2 0z "
    private const val R_TIP = "M20.2 5.2a3.2 3.2 0 0 1 0 5.6a5 5 0 0 0 0-5.6z"

    private class Shape(val data: String, val filled: Boolean, val dx: Float = 0f, val dy: Float = 0f)

    private val shapes = mapOf(
        IconKind.PHONE to Shape("M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z M11 18h2", false),
        IconKind.EARBUDS to Shape(L_TIP + L_HEAD + L_STEM + R_HEAD + R_STEM + R_TIP, true),
        IconKind.BUD_LEFT to Shape(L_TIP + L_HEAD + L_STEM, true, dx = 5.3f),
        IconKind.BUD_RIGHT to Shape(R_HEAD + R_STEM + R_TIP, true, dx = -5.1f),
        IconKind.CASE to Shape("M5 9a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v6a4 4 0 0 1-4 4H9a4 4 0 0 1-4-4z M5 11h14 M11 15h2", false),
        IconKind.WATCH to Shape("M9 2h6l1 4H8z M8 18l1 4h6l1-4 M7 6h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M12 9v3l2 1", false),
        IconKind.HEADPHONES to Shape("M4 15v-3a8 8 0 0 1 16 0v3 M4 15a2 2 0 0 1 2-2h1v7H6a2 2 0 0 1-2-2z M20 15a2 2 0 0 0-2-2h-1v7h1a2 2 0 0 0 2-2z", false),
        IconKind.BLUETOOTH to Shape("M7 7l10 10-5 5V2l5 5L7 17", false)
    )
    private const val BOLT = "M13 2L4 14h7l-1 8 9-12h-7z"

    private val cache = HashMap<String, Path>()
    private fun path(d: String): Path = cache.getOrPut(d) { PathParser.createPathFromPathData(d) }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** Draws [kind] centred on (cx, cy), [size] pixels across. */
    fun draw(c: Canvas, kind: IconKind, cx: Float, cy: Float, size: Float, color: Int) {
        val s = shapes.getValue(kind)
        val k = size / 24f
        val p = Path(path(s.data))
        p.transform(Matrix().apply {
            setTranslate(s.dx, s.dy)
            postScale(k, k)
            postTranslate(cx - size / 2f, cy - size / 2f)
        })
        if (s.filled) {
            // Thin gaps where each bud's head meets its stem, cut right through to the background.
            val layer = c.saveLayer(cx - size, cy - size, cx + size, cy + size, null)
            fill.color = color
            c.drawPath(p, fill)
            cut.strokeWidth = 0.75f * k
            c.drawPath(p, cut)
            c.restoreToCount(layer)
        } else {
            line.color = color
            line.strokeWidth = 1.8f * k
            c.drawPath(p, line)
        }
    }

    fun bolt(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val k = size / 24f
        val p = Path(path(BOLT))
        p.transform(Matrix().apply { setScale(k, k); postTranslate(cx - size / 2f, cy - size / 2f) })
        fill.color = color
        c.drawPath(p, fill)
    }

    /** A small picture of one icon, for the app screen. */
    fun bitmap(kind: IconKind, px: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), kind, px / 2f, px / 2f, px.toFloat(), color)
        return b
    }
}
