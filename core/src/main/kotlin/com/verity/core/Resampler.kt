package com.verity.core

import java.util.stream.IntStream
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Separable Lanczos-3 upscaler. This is interpolation, not super-resolution: it produces a clean
 * 4K-sized file but cannot add detail the sensor never recorded. That is deliberate - generative
 * "AI upscaling" invents texture (and on faces, invents identity), which the brief forbids.
 */
object Resampler {
    private const val A = 3

    fun lanczos(src: ArgbImage, targetLongEdge: Int): ArgbImage {
        val scale = targetLongEdge.toDouble() / src.longEdge
        val ow = (src.width * scale).roundToInt().coerceAtLeast(1)
        val oh = (src.height * scale).roundToInt().coerceAtLeast(1)
        val (hIdx, hW) = weights(src.width, ow)
        val (vIdx, vW) = weights(src.height, oh)
        val taps = 2 * A
        // Horizontal pass into a planar float buffer (src.height x ow).
        val mid = Array(3) { FloatArray(ow * src.height) }
        IntStream.range(0, src.height).parallel().forEach { y ->
            val row = y * src.width
            for (x in 0 until ow) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in 0 until taps) {
                    val wgt = hW[x * taps + k]
                    val p = src.pixels[row + hIdx[x * taps + k]]
                    r += wgt * ((p shr 16) and 0xFF); g += wgt * ((p shr 8) and 0xFF); b += wgt * (p and 0xFF)
                }
                val i = y * ow + x
                mid[0][i] = r; mid[1][i] = g; mid[2][i] = b
            }
        }
        val out = ArgbImage(ow, oh)
        IntStream.range(0, oh).parallel().forEach { y ->
            for (x in 0 until ow) {
                var r = 0f; var g = 0f; var b = 0f
                for (k in 0 until taps) {
                    val wgt = vW[y * taps + k]
                    val i = vIdx[y * taps + k] * ow + x
                    r += wgt * mid[0][i]; g += wgt * mid[1][i]; b += wgt * mid[2][i]
                }
                out.pixels[y * ow + x] = -0x1000000 or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
        }
        return out
    }

    private fun c(v: Float): Int { val i = (v + 0.5f).toInt(); return if (i < 0) 0 else if (i > 255) 255 else i }

    private fun weights(inSize: Int, outSize: Int): Pair<IntArray, FloatArray> {
        val taps = 2 * A
        val idx = IntArray(outSize * taps)
        val wts = FloatArray(outSize * taps)
        val ratio = inSize.toDouble() / outSize
        for (o in 0 until outSize) {
            val center = (o + 0.5) * ratio - 0.5
            val start = floor(center).toInt() - A + 1
            var sum = 0.0
            for (k in 0 until taps) {
                val s = start + k
                val wv = kernel(center - s)
                idx[o * taps + k] = s.coerceIn(0, inSize - 1)
                wts[o * taps + k] = wv.toFloat()
                sum += wv
            }
            for (k in 0 until taps) wts[o * taps + k] = (wts[o * taps + k] / sum).toFloat()
        }
        return idx to wts
    }

    private fun kernel(x: Double): Double {
        if (x == 0.0) return 1.0
        if (x <= -A || x >= A) return 0.0
        val px = PI * x
        return A * sin(px) * sin(px / A) / (px * px)
    }

}
