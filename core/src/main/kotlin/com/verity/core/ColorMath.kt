package com.verity.core

import kotlin.math.pow

/** sRGB transfer functions and Rec.709 luma/chroma helpers, LUT-backed for speed. */
internal object ColorMath {
    const val KR = 0.2126f
    const val KG = 0.7152f
    const val KB = 0.0722f
    private const val CB_SCALE = 1.8556f // 2 * (1 - KB)
    private const val CR_SCALE = 1.5748f // 2 * (1 - KR)

    /** 8-bit sRGB code value -> linear light. */
    val toLinear8 = FloatArray(256) { i -> decode(i / 255f) }

    private const val ENC_N = 8192
    private val encLut = FloatArray(ENC_N + 2) { i -> encodeExact(i.toFloat() / ENC_N) }

    fun decode(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)

    private fun encodeExact(l: Float): Float =
        if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f

    /** Linear light -> sRGB-encoded [0,1] (input is clamped). */
    fun encode(l: Float): Float {
        if (l <= 0f) return 0f
        if (l >= 1f) return 1f
        val f = l * ENC_N
        val i = f.toInt()
        val t = f - i
        return encLut[i] + (encLut[i + 1] - encLut[i]) * t
    }

    fun luma(r: Float, g: Float, b: Float) = KR * r + KG * g + KB * b
    fun cb(b: Float, y: Float) = (b - y) / CB_SCALE
    fun cr(r: Float, y: Float) = (r - y) / CR_SCALE

    /** Y'CbCr -> R'G'B' written into [out]. */
    fun ycc2rgb(y: Float, cb: Float, cr: Float, out: FloatArray) {
        val r = y + CR_SCALE * cr
        val b = y + CB_SCALE * cb
        out[0] = r
        out[1] = (y - KR * r - KB * b) / KG
        out[2] = b
    }

    fun to8(v: Float): Int {
        val i = (v * 255f + 0.5f).toInt()
        return if (i < 0) 0 else if (i > 255) 255 else i
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
