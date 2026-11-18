package com.verity.core

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Final micro-contrast stage at output resolution. Splits luma into a 3x3 Gaussian base and a
 * detail residual, then: residuals at noise amplitude are attenuated (denoise), residuals above it
 * are amplified (sharpen). Result is clamped to the local min/max (+ small margin) so there are
 * no bright/dark halos - the "no excessive sharpening" rule enforced numerically.
 */
internal object DetailPass {

    fun apply(img: ArgbImage, p: GradeParams): ArgbImage {
        if (p.sharpen <= 0f && p.lumaDenoise <= 0f) return img
        val w = img.width; val h = img.height
        val sigma = SceneAnalyzer.estimateNoise(img)
        val t = max(0.004f, 2.5f * sigma)
        val lowGain = 1f - p.lumaDenoise
        val highGain = 1f + 1.6f * p.sharpen
        val src = img.pixels
        val out = ArgbImage(w, h)
        IntStream.range(0, h).parallel().forEach { y ->
            val ys = intArrayOf(max(0, y - 1), y, min(h - 1, y + 1))
            for (x in 0 until w) {
                val xs0 = max(0, x - 1); val xs2 = min(w - 1, x + 1)
                var blur = 0f; var lo = 1f; var hi = 0f
                for (j in 0..2) {
                    val row = ys[j] * w
                    val wy = if (j == 1) 2f else 1f
                    val a = l(src[row + xs0]); val b = l(src[row + x]); val c = l(src[row + xs2])
                    blur += wy * (a + 2f * b + c)
                    lo = min(lo, min(a, min(b, c))); hi = max(hi, max(a, max(b, c)))
                }
                blur /= 16f
                val px = src[y * w + x]
                val yv = l(px)
                val d = yv - blur
                val gain = lowGain + (highGain - lowGain) * ColorMath.smoothstep(t, 3f * t, abs(d))
                val ny = (blur + (d * gain).coerceIn(-0.1f, 0.1f)).coerceIn(lo - 0.02f, hi + 0.02f)
                val dy = ((ny - yv) * 255f).toInt()
                out.pixels[y * w + x] = (px and -0x1000000) or
                    (c8(((px shr 16) and 0xFF) + dy) shl 16) or (c8(((px shr 8) and 0xFF) + dy) shl 8) or c8((px and 0xFF) + dy)
            }
        }
        return out
    }

    private fun l(p: Int) = (0.2126f * ((p shr 16) and 0xFF) + 0.7152f * ((p shr 8) and 0xFF) + 0.0722f * (p and 0xFF)) / 255f
    private fun c8(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v
}
