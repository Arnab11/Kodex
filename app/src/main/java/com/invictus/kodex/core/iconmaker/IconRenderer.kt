package com.invictus.kodex.core.iconmaker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Launcher masks as android Paths (origin top-left, square of [size]). */
object IconShapes {
    fun path(shape: IconShape, size: Float): Path = Path().apply {
        when (shape) {
            IconShape.Circle -> addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            IconShape.Rounded -> addRoundRect(RectF(0f, 0f, size, size), size * 0.22f, size * 0.22f, Path.Direction.CW)
            IconShape.Square -> addRect(0f, 0f, size, size, Path.Direction.CW)
            IconShape.Teardrop -> {
                val r = size / 2f
                val s = size * 0.1f
                // top-left, top-right, bottom-right (the point), bottom-left
                addRoundRect(RectF(0f, 0f, size, size), floatArrayOf(r, r, r, r, s, s, r, r), Path.Direction.CW)
            }
            IconShape.Squircle -> {
                val steps = 120
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val c = cos(t)
                    val s = sin(t)
                    val x = size / 2f + (sign(c) * abs(c).pow(0.5)).toFloat() * size / 2f
                    val y = size / 2f + (sign(s) * abs(s).pow(0.5)).toFloat() * size / 2f
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
}

/**
 * Draws the adaptive-icon layers (108dp canvas, 432px at xxxhdpi) and composites them. The previews and the
 * export both come from here, so what you see is what gets written. Blocking: call off the main thread.
 */
object IconRenderer {
    /** 108dp at xxxhdpi. */
    const val LAYER = 432

    /** The 66dp circle that is never cropped by any launcher mask, as a fraction of the 108dp layer. */
    private const val SAFE = 66f / 108f

    /** Visible 72dp window of the 108dp layer. */
    private const val VISIBLE = 72f / 108f

    fun background(spec: IconSpec, px: Int = LAYER): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        when (spec.bgKind) {
            BgKind.Solid -> c.drawColor(spec.bg1)
            BgKind.Gradient -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val a = Math.toRadians(spec.bgAngle.toDouble())
                val dx = cos(a).toFloat()
                val dy = -sin(a).toFloat()
                val half = px / 2f
                val reach = (abs(dx) + abs(dy)) * half
                p.shader = LinearGradient(
                    half - dx * reach, half - dy * reach, half + dx * reach, half + dy * reach,
                    spec.bg1, spec.bg2, Shader.TileMode.CLAMP,
                )
                c.drawRect(0f, 0f, px.toFloat(), px.toFloat(), p)
            }
        }
        return bmp
    }

    /** Transparent layer with the glyph. [mono] draws opaque white only (the system tints it for themed icons). */
    fun foreground(spec: IconSpec, px: Int = LAYER, mono: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val safe = px * SAFE
        val box = safe * spec.sizePct / 100f
        val cx = px / 2f + safe * spec.offsetXPct / 100f
        val cy = px / 2f + safe * spec.offsetYPct / 100f
        val rect = RectF(cx - box / 2f, cy - box / 2f, cx + box / 2f, cy + box / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.color = if (mono) Color.WHITE else spec.fgColor
        if (spec.shadow && !mono && spec.kind != GlyphKind.Picture) {
            paint.setShadowLayer(px * 0.014f, 0f, px * 0.01f, 0x73000000)
        }
        when (spec.kind) {
            GlyphKind.Symbol -> SymbolLibrary.path(spec.symbol)?.let { drawSymbol(c, it, rect, paint) }
            GlyphKind.Text -> drawText(c, spec.text, spec.font, rect, paint)
            GlyphKind.Picture -> spec.picture?.let { drawPicture(c, it, rect, spec, mono) }
        }
        return bmp
    }

    /**
     * Status-bar small icon: the glyph in opaque white on a transparent [px] square (Android only reads the
     * alpha and tints it), centred with [notif]'s size and offsets.
     */
    fun notification(spec: IconSpec, notif: NotifSpec, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val box = px * notif.sizePct / 100f
        val cx = px / 2f + px * notif.offsetXPct / 100f
        val cy = px / 2f + px * notif.offsetYPct / 100f
        val rect = RectF(cx - box / 2f, cy - box / 2f, cx + box / 2f, cy + box / 2f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        when (spec.kind) {
            GlyphKind.Symbol -> SymbolLibrary.path(spec.symbol)?.let { drawSymbol(c, it, rect, paint) }
            GlyphKind.Text -> drawText(c, spec.text, spec.font, rect, paint)
            GlyphKind.Picture -> spec.picture?.let { drawPicture(c, it, rect, spec, mono = true) }
        }
        return bmp
    }

    /** A finished launcher icon: the visible 72dp window of both layers, cut by [shape]. */
    fun composite(bg: Bitmap, fg: Bitmap, shape: IconShape, outPx: Int): Bitmap {
        val out = Bitmap.createBitmap(outPx, outPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawPath(IconShapes.path(shape, outPx.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG))
        val inset = (bg.width * (1f - VISIBLE) / 2f).toInt()
        val src = Rect(inset, inset, bg.width - inset, bg.height - inset)
        val dst = Rect(0, 0, outPx, outPx)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
        c.drawBitmap(bg, src, dst, p)
        c.drawBitmap(fg, src, dst, p)
        return out
    }

    private fun drawSymbol(c: Canvas, glyph: Path, rect: RectF, paint: Paint) {
        val m = Matrix()
        m.setScale(rect.width() / 24f, rect.height() / 24f)
        m.postTranslate(rect.left, rect.top)
        val out = Path(glyph)
        out.transform(m)
        c.drawPath(out, paint)
    }

    private fun typefaceFor(font: TextFont): Typeface = when (font) {
        TextFont.Bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        TextFont.Light -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        TextFont.Serif -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        TextFont.Mono -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        TextFont.Script -> Typeface.create("cursive", Typeface.NORMAL)
    }

    private fun drawText(c: Canvas, text: String, font: TextFont, rect: RectF, paint: Paint) {
        val t = text.trim().take(3)
        if (t.isEmpty()) return
        paint.typeface = typefaceFor(font)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = rect.height()
        val b = Rect()
        paint.getTextBounds(t, 0, t.length, b)
        val k = min(rect.width() / b.width().coerceAtLeast(1), rect.height() / b.height().coerceAtLeast(1))
        paint.textSize = rect.height() * k
        paint.getTextBounds(t, 0, t.length, b)
        c.drawText(t, rect.centerX(), rect.centerY() - b.exactCenterY(), paint)
    }

    private fun drawPicture(c: Canvas, bmp: Bitmap, rect: RectF, spec: IconSpec, mono: Boolean) {
        val s = min(rect.width() / bmp.width, rect.height() / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        val dst = RectF(rect.centerX() - w / 2f, rect.centerY() - h / 2f, rect.centerX() + w / 2f, rect.centerY() + h / 2f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (mono) p.colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        else if (spec.tintPicture) p.colorFilter = PorterDuffColorFilter(spec.fgColor, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(bmp, null, dst, p)
    }
}
