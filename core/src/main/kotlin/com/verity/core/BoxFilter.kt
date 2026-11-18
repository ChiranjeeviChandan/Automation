package com.verity.core

/**
 * O(n) separable mean filter with a (2r+1)^2 window, normalised by the number of in-bounds
 * samples so borders are not darkened. Building block for the guided filter.
 */
internal object BoxFilter {
    fun mean(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val row = DoubleArray(w + 1)
        for (y in 0 until h) {
            val o = y * w
            row[0] = 0.0
            for (x in 0 until w) row[x + 1] = row[x] + src[o + x]
            for (x in 0 until w) {
                val a = maxOf(0, x - r)
                val b = minOf(w, x + r + 1)
                tmp[o + x] = ((row[b] - row[a]) / (b - a)).toFloat()
            }
        }
        val col = DoubleArray(h + 1)
        for (x in 0 until w) {
            col[0] = 0.0
            for (y in 0 until h) col[y + 1] = col[y] + tmp[y * w + x]
            for (y in 0 until h) {
                val a = maxOf(0, y - r)
                val b = minOf(h, y + r + 1)
                out[y * w + x] = ((col[b] - col[a]) / (b - a)).toFloat()
            }
        }
        return out
    }
}

/**
 * Linear coefficients (q = a * guide + b) of a guided filter (He et al.). Computed on a
 * low-resolution proxy and bilinearly upsampled per full-res pixel ("fast guided filter"),
 * which gives edge-aware smoothing of a 12 MP frame without any full-resolution float buffers.
 */
internal class GuidedCoefficients(val w: Int, val h: Int, val a: FloatArray, val b: FloatArray) {

    /** Evaluate q at low-res continuous coordinate (u, v) for guide value [guide]. */
    fun eval(u: Float, v: Float, guide: Float): Float {
        val x0 = u.toInt().coerceIn(0, w - 1)
        val y0 = v.toInt().coerceIn(0, h - 1)
        val x1 = minOf(x0 + 1, w - 1)
        val y1 = minOf(y0 + 1, h - 1)
        val fx = (u - x0).coerceIn(0f, 1f)
        val fy = (v - y0).coerceIn(0f, 1f)
        val i00 = y0 * w + x0; val i10 = y0 * w + x1; val i01 = y1 * w + x0; val i11 = y1 * w + x1
        val av = lerp(lerp(a[i00], a[i10], fx), lerp(a[i01], a[i11], fx), fy)
        val bv = lerp(lerp(b[i00], b[i10], fx), lerp(b[i01], b[i11], fx), fy)
        return av * guide + bv
    }

    private fun lerp(p: Float, q: Float, t: Float) = p + (q - p) * t

    companion object {
        fun compute(guide: FloatArray, input: FloatArray, w: Int, h: Int, r: Int, eps: Float): GuidedCoefficients {
            val n = w * h
            val meanI = BoxFilter.mean(guide, w, h, r)
            val meanP = BoxFilter.mean(input, w, h, r)
            val ip = FloatArray(n) { guide[it] * input[it] }
            val ii = FloatArray(n) { guide[it] * guide[it] }
            val corrIp = BoxFilter.mean(ip, w, h, r)
            val corrIi = BoxFilter.mean(ii, w, h, r)
            val a = FloatArray(n)
            val b = FloatArray(n)
            for (i in 0 until n) {
                val varI = corrIi[i] - meanI[i] * meanI[i]
                val cov = corrIp[i] - meanI[i] * meanP[i]
                a[i] = cov / (varI + eps)
                b[i] = meanP[i] - a[i] * meanI[i]
            }
            return GuidedCoefficients(w, h, BoxFilter.mean(a, w, h, r), BoxFilter.mean(b, w, h, r))
        }
    }
}
