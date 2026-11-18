package com.verity.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Global tone curve applied to linear luminance (then to RGB as a ratio, which preserves hue and
 * keeps skin tones from shifting). Monotonic by construction for every in-range parameter set.
 */
internal class ToneCurve(p: GradeParams) {
    private val maxIn = 4f
    private val n = 8192
    private val lut = FloatArray(n + 2)

    init {
        val bp = p.blackPoint
        val knee = 0.9f - 0.45f * p.highlights
        for (i in 0..n + 1) {
            val x = i * maxIn / n
            var v = ((x - bp) / (1f - bp)).coerceAtLeast(0f)
            // Highlight shoulder: slope 1 at the knee, asymptotically approaches 1 (no hard clip).
            if (v > knee) v = knee + (1f - knee) * (1f - exp(-(v - knee) / (1f - knee)))
            var g = v.pow(1f / 2.2f) // perceptual domain
            // Shadow lift: bump concentrated in the lowest quarter, endpoints fixed.
            g += p.shadows * 1.5f * g * (1f - g).pow(4)
            // Midtone contrast around a 0.45 pivot, endpoints fixed.
            g += p.contrast * (g - 0.45f) * 4f * g * (1f - g)
            lut[i] = g.coerceIn(0f, 1f).pow(2.2f)
        }
    }

    fun apply(x: Float): Float {
        if (x <= 0f) return 0f
        val f = (x / maxIn).coerceAtMost(1f) * n
        val i = f.toInt()
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }

    fun isMonotonic(): Boolean = (1..n).all { lut[it] + 1e-6f >= lut[it - 1] } && abs(lut[0]) < 1e-6f
}
