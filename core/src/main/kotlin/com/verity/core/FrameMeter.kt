package com.verity.core

import kotlin.math.abs
import kotlin.math.min

/**
 * Per-frame measurements on a camera Y (luma) plane, plus the monitoring overlays a pro camera shows:
 * focus peaking, zebra stripes on blown highlights and a luma histogram. Pure Kotlin so it is
 * unit-testable; the app feeds it ImageAnalysis frames (~640x480).
 */
class FrameMeter {
    private var prevSmall: IntArray? = null

    class Result(
        val measure: FrameMeasure,
        /** 64-bin luma histogram, normalised to max 1. */
        val histogram: FloatArray,
        /** ARGB overlay at frame size: peaking dots (amber) and zebras (red stripes); transparent elsewhere. */
        val overlay: IntArray?,
    )

    /**
     * @param y luma plane, [rowStride] bytes per row.
     * @param face optional face box in frame pixel coordinates (left, top, right, bottom).
     */
    fun measure(
        y: ByteArray, width: Int, height: Int, rowStride: Int,
        face: IntArray? = null, focus: IntArray? = null,
        peaking: Boolean = true, zebras: Boolean = true,
    ): Result {
        val hist = IntArray(64)
        var sum = 0L; var clipped = 0; var crushed = 0
        val n = width * height
        val lum = IntArray(n)
        for (r in 0 until height) {
            val o = r * rowStride
            for (c in 0 until width) {
                val v = y[o + c].toInt() and 0xFF
                lum[r * width + c] = v
                sum += v; hist[v shr 2]++
                if (v >= 250) clipped++ else if (v <= 5) crushed++
            }
        }
        val sorted = hist.copyOf()
        var acc = 0; var p99 = 255
        for (b in 63 downTo 0) { acc += sorted[b]; if (acc >= n / 100) { p99 = b * 4 + 2; break } }

        val faceLuma = face?.let { medianIn(lum, width, height, inset(it, 0.2f)) }

        // Motion: mean absolute difference on a 40x30 grid between consecutive frames.
        val gw = 40; val gh = 30
        val small = IntArray(gw * gh) { i -> lum[min(height - 1, (i / gw) * height / gh) * width + min(width - 1, (i % gw) * width / gw)] }
        val motion = prevSmall?.let { p -> var d = 0; for (i in small.indices) d += abs(small[i] - p[i]); d.toFloat() / small.size / 40f } ?: 0f
        prevSmall = small

        // Gradient magnitude (Sobel-ish, 2 taps) for peaking and focus score.
        var overlay: IntArray? = null
        var focusAcc = 0L; var focusCnt = 0
        val fr = focus ?: face ?: intArrayOf(width / 3, height / 3, 2 * width / 3, 2 * height / 3)
        if (peaking || zebras) overlay = IntArray(n)
        // Peaking threshold adapts to the frame so it highlights the *sharpest* edges, not all texture.
        var gsum = 0L
        val grad = IntArray(n)
        for (r in 1 until height - 1) for (c in 1 until width - 1) {
            val i = r * width + c
            val g = abs(lum[i + 1] - lum[i - 1]) + abs(lum[i + width] - lum[i - width])
            grad[i] = g; gsum += g
            if (c in fr[0] until fr[2] && r in fr[1] until fr[3]) { focusAcc += g.toLong() * g; focusCnt++ }
        }
        val mean = gsum.toFloat() / n
        val thr = maxOf(48f, mean * 6f)
        if (overlay != null) for (i in 0 until n) {
            val r = i / width; val c = i % width
            if (zebras && lum[i] >= 248 && ((r + c) / 6) % 2 == 0) overlay[i] = 0xCCFF3030.toInt()
            else if (peaking && grad[i] > thr) overlay[i] = 0xFFFFC23D.toInt()
        }
        val focusScore = if (focusCnt == 0) 0f else (kotlin.math.sqrt(focusAcc.toDouble() / focusCnt) / 60.0).toFloat().coerceIn(0f, 1f)

        val mx = hist.max().coerceAtLeast(1)
        return Result(
            FrameMeasure(
                meanLuma = sum.toFloat() / n / 255f, p99Luma = p99 / 255f,
                clipped = clipped.toFloat() / n, crushed = crushed.toFloat() / n,
                faceLuma = faceLuma?.div(255f), motion = motion.coerceIn(0f, 1f), focusScore = focusScore,
            ),
            FloatArray(64) { hist[it].toFloat() / mx },
            overlay,
        )
    }

    private fun inset(r: IntArray, f: Float): IntArray {
        val w = r[2] - r[0]; val h = r[3] - r[1]
        return intArrayOf(r[0] + (w * f).toInt(), r[1] + (h * f).toInt(), r[2] - (w * f).toInt(), r[3] - (h * f).toInt())
    }

    private fun medianIn(lum: IntArray, w: Int, h: Int, r: IntArray): Int? {
        val x0 = r[0].coerceIn(0, w - 1); val x1 = r[2].coerceIn(x0 + 1, w)
        val y0 = r[1].coerceIn(0, h - 1); val y1 = r[3].coerceIn(y0 + 1, h)
        val hist = IntArray(256); var cnt = 0
        for (yy in y0 until y1) for (xx in x0 until x1) { hist[lum[yy * w + xx]]++; cnt++ }
        if (cnt == 0) return null
        var a = 0
        for (v in 0..255) { a += hist[v]; if (a >= cnt / 2) return v }
        return null
    }
}
