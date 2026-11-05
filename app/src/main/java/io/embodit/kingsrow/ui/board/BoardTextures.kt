package io.embodit.kingsrow.ui.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import io.embodit.kingsrow.domain.Layout
import io.embodit.kingsrow.domain.Sq
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Canvas textures (Xiangqi BoardTextures pattern). Board face: light oak with walnut dark squares, a 4 cm oak frame
 * with an inlay line and the a-h / 1-8 coordinates (8 mm). The bitmap's top edge is the board's FAR edge (row 0)
 * and its left edge file a: the face plane is laid flat with pitch -90°, which maps bitmap-up to -Z (away from the
 * player), so a1 lands near-left. Piece caps: a disc with the turned rings and the crown (dim on a man, gold on a king).
 */
object BoardTextures {
    private fun grain(c: Canvas, x0: Float, y0: Float, w: Float, h: Float, rnd: Random, base: Int, dark: Int, light: Int, lines: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = base
        c.drawRect(x0, y0, x0 + w, y0 + h, p)
        for (i in 0 until lines) {
            val y = y0 + rnd.nextFloat() * h
            p.color = if (rnd.nextFloat() < 0.75f) dark else light
            p.alpha = 14 + rnd.nextInt(30)
            p.strokeWidth = 1f + rnd.nextFloat() * 4f
            val wob = rnd.nextFloat() * 10f - 5f
            c.drawLine(x0, y, x0 + w, y + wob, p)
        }
    }

    fun boardFace(size: Int = 2048): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(21)
        val k = size / Layout.BOARD
        val border = (Layout.HALF - 4 * Layout.PITCH) * k
        val sq = Layout.PITCH * k
        // oak frame
        grain(c, 0f, 0f, size.toFloat(), size.toFloat(), rnd, Color.rgb(0xC9, 0xA2, 0x6E), Color.rgb(0x8A, 0x63, 0x38), Color.rgb(0xEE, 0xD6, 0xA8), 420)
        // squares
        for (r in 0 until 8) for (col in 0 until 8) {
            val x = border + col * sq
            val y = border + r * sq
            if (Sq.dark(col, r)) grain(c, x, y, sq, sq, rnd, Color.rgb(0x6B, 0x4A, 0x2E), Color.rgb(0x3E, 0x28, 0x16), Color.rgb(0x8C, 0x66, 0x42), 40)
            else grain(c, x, y, sq, sq, rnd, Color.rgb(0xE6, 0xCC, 0x9C), Color.rgb(0xB0, 0x88, 0x56), Color.rgb(0xF6, 0xE6, 0xC4), 30)
        }
        // inlay line round the field
        val inlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = size / 300f; color = Color.rgb(0x3A, 0x26, 0x14) }
        c.drawRect(border - size / 260f, border - size / 260f, size - border + size / 260f, size - border + size / 260f, inlay)
        // coordinates: 8 mm cap height
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0x3A, 0x26, 0x14)
            textSize = 0.0112f * k
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        for (col in 0 until 8) c.drawText("${'a' + col}", border + (col + 0.5f) * sq, size - border / 2 + text.textSize * 0.36f, text)
        for (r in 0 until 8) c.drawText("${8 - r}", border / 2, border + (r + 0.5f) * sq + text.textSize * 0.36f, text)
        return bmp
    }

    /** "F↑" orientation test (profile D10): the F must read upright from the player with its arrow pointing away. */
    fun orientationTest(size: Int = 512): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size * 0.6f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
        c.drawText("F↑", size / 2f, size * 0.7f, p)
        return bmp
    }

    /** A piece's top: [body] colour, two turned rings and a crown ([king] = gold, otherwise a dim relief). */
    fun cap(body: Int, king: Boolean, size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = size / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx * 0.8f, cx * 0.7f, r * 1.1f, lighten(body, 0.18f), body, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = size / 40f
        p.color = darken(body, 0.35f)
        c.drawCircle(cx, cx, r * 0.9f, p)
        p.color = lighten(body, 0.12f)
        p.strokeWidth = size / 80f
        c.drawCircle(cx, cx, r * 0.84f, p)
        p.color = darken(body, 0.25f)
        c.drawCircle(cx, cx, r * 0.62f, p)
        // crown
        p.style = Paint.Style.FILL
        p.color = if (king) Color.rgb(0xF0, 0xC2, 0x4E) else lighten(body, 0.10f)
        val w = r * 0.78f
        val h = r * 0.5f
        val top = cx - h * 0.55f
        val base = cx + h * 0.45f
        val path = Path().apply {
            moveTo(cx - w / 2, base)
            lineTo(cx - w / 2, top + h * 0.35f)
            lineTo(cx - w / 4, top + h * 0.62f)
            lineTo(cx, top)
            lineTo(cx + w / 4, top + h * 0.62f)
            lineTo(cx + w / 2, top + h * 0.35f)
            lineTo(cx + w / 2, base)
            close()
        }
        c.drawPath(path, p)
        c.drawRect(cx - w / 2, base + h * 0.08f, cx + w / 2, base + h * 0.24f, p)
        for (i in -2..2) {
            val a = i * 0.5f
            val px = cx + (w / 2) * sin(a)
            val py = top + h * 0.2f + (h * 0.15f) * (1 - cos(a))
            c.drawCircle(if (i == 0) cx else px, if (i == 0) top else py, size / 42f, p)
        }
        if (king) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = size / 90f
            p.color = Color.rgb(0x7A, 0x52, 0x10)
            c.drawPath(path, p)
        }
        return bmp
    }

    private fun lighten(color: Int, k: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * k).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * k).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * k).toInt(),
    )

    private fun darken(color: Int, k: Float): Int = Color.rgb(
        (Color.red(color) * (1 - k)).toInt(), (Color.green(color) * (1 - k)).toInt(), (Color.blue(color) * (1 - k)).toInt(),
    )
}
