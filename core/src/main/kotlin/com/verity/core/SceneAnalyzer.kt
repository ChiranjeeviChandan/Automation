package com.verity.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Measurements the auto-grader and the AI director reason over. Luminance values are linear light. */
data class SceneStats(
    val width: Int,
    val height: Int,
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    /** Per-channel gains (G = 1) that would neutralise the estimated illuminant. */
    val neutralGains: FloatArray,
    /** Fraction of pixels with a channel at or near 255. */
    val clippedFraction: Float,
    /** Fraction of pixels in the skin-tone chroma region. */
    val skinFraction: Float,
    val foliageFraction: Float,
    val skyFraction: Float,
    /** Mean chroma of the encoded image (0 = monochrome). */
    val colorfulness: Float,
    /** Estimated luma noise sigma on the 0..1 encoded scale. */
    val noiseSigma: Float,
    /** Dynamic range between p05 and p95 in stops. */
    val dynamicRangeStops: Float,
) {
    /** Magnitude of the colour cast: max/min neutralising gain. 1 = neutral. */
    val castStrength: Float get() = neutralGains.max() / neutralGains.min()

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/** Low-resolution, linear-light working copy shared by analysis and the guided-filter proxies. */
internal class LinearProxy(val w: Int, val h: Int, val scale: Float, val r: FloatArray, val g: FloatArray, val b: FloatArray)

object SceneAnalyzer {

    /** Long edge of the analysis/filter proxy. ~0.4 MP keeps analysis well under 100 ms on a phone. */
    internal const val PROXY_LONG_EDGE = 768

    internal fun proxy(img: ArgbImage, longEdge: Int = PROXY_LONG_EDGE): LinearProxy {
        val s = max(1, (img.longEdge + longEdge - 1) / longEdge)
        val w = max(1, img.width / s)
        val h = max(1, img.height / s)
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        val lut = ColorMath.toLinear8
        val inv = 1f / (s * s)
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0f; var sg = 0f; var sb = 0f
            for (dy in 0 until s) {
                val row = (y * s + dy) * img.width + x * s
                for (dx in 0 until s) {
                    val p = img.pixels[row + dx]
                    sr += lut[(p shr 16) and 0xFF]; sg += lut[(p shr 8) and 0xFF]; sb += lut[p and 0xFF]
                }
            }
            val i = y * w + x
            r[i] = sr * inv; g[i] = sg * inv; b[i] = sb * inv
        }
        return LinearProxy(w, h, s.toFloat(), r, g, b)
    }

    fun analyze(img: ArgbImage): SceneStats {
        val p = proxy(img)
        val n = p.w * p.h
        val lum = FloatArray(n)
        var clipped = 0; var skin = 0; var foliage = 0; var sky = 0
        var chromaSum = 0.0
        for (i in 0 until n) {
            val r = p.r[i]; val g = p.g[i]; val b = p.b[i]
            val y = ColorMath.luma(r, g, b)
            lum[i] = y
            if (max(r, max(g, b)) > 0.96f) clipped++
            val er = ColorMath.encode(r); val eg = ColorMath.encode(g); val eb = ColorMath.encode(b)
            val ey = ColorMath.luma(er, eg, eb)
            val cb = ColorMath.cb(eb, ey); val cr = ColorMath.cr(er, ey)
            chromaSum += sqrt((cb * cb + cr * cr).toDouble())
            if (isSkin(ey, cb, cr)) skin++
            if (eg > er * 1.08f && eg > eb * 1.05f && ey in 0.08f..0.85f) foliage++
            if (eb > er * 1.15f && eb >= eg && ey > 0.35f) sky++
        }
        val sorted = lum.copyOf().also { it.sort() }
        fun pct(q: Float) = sorted[((n - 1) * q).toInt()]

        val gains = estimateIlluminant(p)
        val p05 = pct(0.05f); val p95 = pct(0.95f)
        return SceneStats(
            width = img.width, height = img.height,
            p01 = pct(0.01f), p05 = p05, p50 = pct(0.5f), p95 = p95, p99 = pct(0.99f),
            neutralGains = gains,
            clippedFraction = clipped.toFloat() / n,
            skinFraction = skin.toFloat() / n,
            foliageFraction = foliage.toFloat() / n,
            skyFraction = sky.toFloat() / n,
            colorfulness = (chromaSum / n).toFloat(),
            noiseSigma = estimateNoise(img),
            dynamicRangeStops = (ln(max(p95, 1e-4f) / max(p05, 1e-4f)) / ln(2f)),
        )
    }

    /**
     * Illuminant estimate: grey-world over well-exposed pixels, then refined twice using only the
     * pixels that look neutral *after* the current correction (so a strong cast does not hide the
     * very greys we are looking for). Large single-colour surfaces get excluded by the refinement.
     */
    private fun estimateIlluminant(p: LinearProxy): FloatArray {
        var gr = 1f; var gb = 1f
        for (iter in 0 until 3) {
            var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
            for (i in p.r.indices) {
                val r = p.r[i] * gr; val g = p.g[i]; val b = p.b[i] * gb
                val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                val y = ColorMath.luma(r, g, b)
                if (y !in 0.02f..0.8f || p.r[i] > 0.95f || p.g[i] > 0.95f || p.b[i] > 0.95f) continue
                if (iter > 0 && (mn <= 0f || mx / mn > 1.5f)) continue
                sr += p.r[i]; sg += p.g[i]; sb += p.b[i]; cnt++
            }
            if (cnt < p.r.size / 100 || sr <= 0 || sb <= 0) break
            // Clamp so a dominant subject colour can never cause a wild correction.
            gr = (sg / sr).toFloat().coerceIn(0.5f, 2.2f)
            gb = (sg / sb).toFloat().coerceIn(0.5f, 2.2f)
        }
        return floatArrayOf(gr, 1f, gb)
    }

    /** Skin locus in Y'CbCr - wide enough for all skin tones, deliberately not brightness-dependent. */
    internal fun isSkin(y: Float, cb: Float, cr: Float): Boolean =
        y in 0.12f..0.95f && cb in -0.2f..0.01f && cr in 0.02f..0.2f && cr > -cb * 0.5f

    /**
     * Robust luma noise estimate: median absolute response of a Laplacian over a sparse full-res
     * sample, scaled to sigma (kernel energy 20). Edges are outliers and the median ignores them.
     */
    fun estimateNoise(img: ArgbImage): Float {
        val w = img.width; val h = img.height
        if (w < 8 || h < 8) return 0f
        val step = max(1, ((w.toLong() * h) / 60_000L).toInt().let { sqrt(it.toDouble()).toInt() })
        val samples = ArrayList<Float>()
        fun l(x: Int, y: Int): Float {
            val p = img.pixels[y * w + x]
            return (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
        }
        var y = 1
        while (y < h - 1) {
            var x = 1
            while (x < w - 1) {
                val c = l(x, y)
                if (c in 0.05f..0.95f) {
                    samples += abs(l(x - 1, y) + l(x + 1, y) + l(x, y - 1) + l(x, y + 1) - 4f * c)
                }
                x += step
            }
            y += step
        }
        if (samples.isEmpty()) return 0f
        samples.sort()
        return samples[samples.size / 2] * 1.4826f / sqrt(20f)
    }
}
