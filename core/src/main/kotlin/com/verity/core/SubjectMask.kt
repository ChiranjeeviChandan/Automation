package com.verity.core

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Soft subject mask for local dodge & burn, built at proxy resolution.
 *
 * Each face becomes a head-sized soft ellipse; the mask is then snapped to real edges with a guided
 * filter on the image's luminance, so the boundary follows hair against a wall instead of drawing a
 * glowing halo around the head. Full-resolution pixels sample it bilinearly.
 */
internal class SubjectMask private constructor(
    private val w: Int, private val h: Int, private val weight: FloatArray,
    private val subjectGain: Float, private val backgroundGain: Float,
) {
    fun gain(i: Int): Float = mix(weight[i])

    fun gainAt(u: Float, v: Float): Float {
        val x0 = u.toInt().coerceIn(0, w - 1); val y0 = v.toInt().coerceIn(0, h - 1)
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val fx = (u - x0).coerceIn(0f, 1f); val fy = (v - y0).coerceIn(0f, 1f)
        val a = weight[y0 * w + x0] + (weight[y0 * w + x1] - weight[y0 * w + x0]) * fx
        val b = weight[y1 * w + x0] + (weight[y1 * w + x1] - weight[y1 * w + x0]) * fx
        return mix(a + (b - a) * fy)
    }

    private fun mix(m: Float) = backgroundGain + (subjectGain - backgroundGain) * m

    companion object {
        fun build(p: LinearProxy, subjects: List<NormRect>, params: GradeParams): SubjectMask? {
            if (subjects.isEmpty() || (params.subjectEv == 0f && params.backgroundEv == 0f)) return null
            val n = p.w * p.h
            val raw = FloatArray(n)
            for (s in subjects) {
                // Face ellipse (the face box, slightly rounded out) with a short soft shoulder. Kept tight on
                // purpose: anything wider lifts the wall beside the head and paints a glow around it.
                val cx = (s.left + s.right) / 2f * p.w
                val cy = (s.top + s.bottom) / 2f * p.h
                val rx = (s.right - s.left) * p.w * 0.5f
                val ry = (s.bottom - s.top) * p.h * 0.52f
                for (y in 0 until p.h) for (x in 0 until p.w) {
                    val dx = (x - cx) / rx; val dy = (y - cy) / ry
                    val d2 = dx * dx + dy * dy
                    val v = if (d2 <= 1f) 1f else exp(-(d2 - 1f) * 8f)
                    val i = y * p.w + x
                    if (v > raw[i]) raw[i] = v
                }
            }
            // Guided by perceptual luminance so the mask follows the subject's silhouette.
            val guide = FloatArray(n) { ColorMath.luma(p.r[it], p.g[it], p.b[it]).pow(1f / 2.2f) }
            val r = max(2, max(p.w, p.h) / 128) // small window: soften only across real edges
            val coef = GuidedCoefficients.compute(guide, raw, p.w, p.h, r, 0.004f)
            val refined = FloatArray(n) { i -> coef.eval((i % p.w).toFloat(), (i / p.w).toFloat(), guide[i]).coerceIn(0f, 1f) }
            return SubjectMask(p.w, p.h, refined, 2f.pow(params.subjectEv), 2f.pow(params.backgroundEv))
        }
    }
}
