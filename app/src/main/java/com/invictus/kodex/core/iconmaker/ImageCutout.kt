package com.invictus.kodex.core.iconmaker

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * "Remove background" for imported artwork: the plain colour around the subject is found from the image's
 * corners and erased by flood-filling inwards from the edges, so same-coloured areas inside the subject survive.
 * The last result is cached, so dragging other sliders does not redo the work. Blocking: call off the main thread.
 */
object ImageCutout {
    private var cacheSource: Bitmap? = null
    private var cacheTolerance = -1
    private var cacheResult: Bitmap? = null

    @Synchronized
    fun removeBackground(src: Bitmap, tolerance: Int): Bitmap {
        if (cacheSource === src && cacheTolerance == tolerance) cacheResult?.let { return it }
        val result = compute(src, tolerance)
        cacheSource = src
        cacheTolerance = tolerance
        cacheResult = result
        return result
    }

    private fun compute(src: Bitmap, tolerance: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Reference = the corner colour closest to the other three (robust against one odd corner).
        val corners = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        val ref = corners.minByOrNull { c -> corners.sumOf { distance2(c, it).toLong() } } ?: corners[0]
        val limit = tolerance.coerceIn(0, 100) * 4.4f
        val limit2 = (limit * limit).toInt()

        fun background(i: Int): Boolean {
            val c = px[i]
            return (c ushr 24) < 16 || distance2(c, ref) <= limit2
        }

        val removed = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun push(i: Int) {
            if (!removed[i] && background(i)) {
                removed[i] = true
                stack[top++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }

        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            if (removed[i]) {
                out[i] = 0
                continue
            }
            // soften the cut edge by one pixel so it does not look jagged
            val x = i % w
            val y = i / w
            val edge = (x > 0 && removed[i - 1]) || (x < w - 1 && removed[i + 1]) ||
                (y > 0 && removed[i - w]) || (y < h - 1 && removed[i + w])
            val c = px[i]
            out[i] = if (edge) ((((c ushr 24) / 2) shl 24) or (c and 0xFFFFFF)) else c
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun distance2(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr * dr + dg * dg + db * db
    }
}
