package com.verity.core

import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The grading pipeline. Order of operations (each step chosen so it cannot alter identity):
 *
 *  1. White balance + exposure        - per-channel gains in linear light
 *  2. Tone curve                      - on luminance, applied to RGB as a ratio (hue-preserving)
 *  3. Soft gamut mapping              - desaturate toward luminance instead of clipping channels
 *  4. Local contrast ("clarity")      - fast guided filter base/detail split, midtones only, no halos
 *  5. Chroma noise reduction          - luma-guided filter on Cb/Cr with edge-preserving coring
 *  6. Colour grade                    - vibrance, saturation, hue bands, with skin-tone shielding
 *  7. Optional square profile crop    - framing only
 *  8. Optional upscale to 4K          - Lanczos-3 interpolation; no generated detail
 *  9. Detail pass                     - noise coring + overshoot-limited sharpening at output size
 */
object Renderer {

    fun render(src: ArgbImage, stats: SceneStats, params: GradeParams, options: RenderOptions = RenderOptions()): ArgbImage {
        val p = params.clamped()
        val stage = ToneStage(p, stats)

        // --- proxies for the guided filters (computed once at ~0.4 MP) ---
        val proxy = SceneAnalyzer.proxy(src)
        val pn = proxy.w * proxy.h
        val py = FloatArray(pn); val pcb = FloatArray(pn); val pcr = FloatArray(pn)
        val tmp = FloatArray(3)
        for (i in 0 until pn) {
            stage.tone(proxy.r[i], proxy.g[i], proxy.b[i], tmp)
            py[i] = tmp[0]; pcb[i] = tmp[1]; pcr[i] = tmp[2]
        }
        val clarityRadius = max(2, max(proxy.w, proxy.h) / 64)
        val baseCoef = GuidedCoefficients.compute(py, py, proxy.w, proxy.h, clarityRadius, 0.01f)
        val cbCoef = GuidedCoefficients.compute(py, pcb, proxy.w, proxy.h, 2, 4e-4f)
        val crCoef = GuidedCoefficients.compute(py, pcr, proxy.w, proxy.h, 2, 4e-4f)
        val chromaT = 0.01f + stats.noiseSigma * 2f

        // --- full-resolution tone + colour pass (row-parallel, no full-res float buffers) ---
        val w = src.width
        val graded = ArgbImage(w, src.height)
        val lut = ColorMath.toLinear8
        val inv = 1f / proxy.scale
        IntStream.range(0, src.height).parallel().forEach { y ->
            val ycc = FloatArray(3)
            val rgb = FloatArray(3)
            val v = (y + 0.5f) * inv - 0.5f
            for (x in 0 until w) {
                val px = src.pixels[y * w + x]
                stage.tone(lut[(px shr 16) and 0xFF], lut[(px shr 8) and 0xFF], lut[px and 0xFF], ycc)
                val u = (x + 0.5f) * inv - 0.5f
                val y0 = ycc[0]
                // Clarity: amplify the edge-aware detail layer, fading out in deep shadows/highlights.
                val detail = (y0 - baseCoef.eval(u, v, y0)).coerceIn(-0.15f, 0.15f)
                val mid = 4f * y0 * (1f - y0)
                val yy = (y0 + p.clarity * 1.6f * detail * mid).coerceIn(0f, 1f)
                // Chroma NR: snap to the luma-guided estimate only where the difference looks like noise.
                val cb = coreChroma(ycc[1], cbCoef.eval(u, v, y0), chromaT, p.chromaDenoise)
                val cr = coreChroma(ycc[2], crCoef.eval(u, v, y0), chromaT, p.chromaDenoise)
                stage.grade(yy, cb, cr, rgb)
                graded.pixels[y * w + x] = (px and -0x1000000) or
                    (ColorMath.to8(rgb[0]) shl 16) or (ColorMath.to8(rgb[1]) shl 8) or ColorMath.to8(rgb[2])
            }
        }

        var out = graded
        options.profileSubject?.let { out = out.crop(ProfileCrop.squareFor(out.width, out.height, it.toPixels(out.width, out.height))) }
        if (options.targetLongEdge > out.longEdge) out = Resampler.lanczos(out, options.targetLongEdge)
        return DetailPass.apply(out, p)
    }

    private fun coreChroma(c: Float, guided: Float, t: Float, strength: Float): Float {
        val d = c - guided
        val keep = ColorMath.smoothstep(t, 3f * t, abs(d))
        return guided + d * (1f - strength * (1f - keep))
    }
}

/** Per-pixel tone and colour math, shared by the proxy and the full-resolution pass. */
internal class ToneStage(private val p: GradeParams, stats: SceneStats) {
    private val curve = ToneCurve(p)
    private val gr: Float
    private val gg: Float
    private val gb: Float

    init {
        var r = 1f + (stats.neutralGains[0] - 1f) * p.wbStrength
        var g = 1f + (stats.neutralGains[1] - 1f) * p.wbStrength
        var b = 1f + (stats.neutralGains[2] - 1f) * p.wbStrength
        r *= 1f + 0.12f * p.temperature
        b *= 1f - 0.12f * p.temperature
        g *= 1f - 0.06f * p.tint
        val norm = ColorMath.luma(r, g, b) // keep overall brightness; exposure is separate
        val e = 2f.pow(p.exposureEv) / norm
        gr = r * e; gg = g * e; gb = b * e
    }

    /** Linear RGB in -> Y'CbCr (encoded) out. */
    fun tone(r0: Float, g0: Float, b0: Float, out: FloatArray) {
        var r = r0 * gr; var g = g0 * gg; var b = b0 * gb
        val yl = max(ColorMath.luma(r, g, b), 1e-5f)
        val yt = curve.apply(yl)
        val k = yt / yl
        r *= k; g *= k; b *= k
        val m = max(r, max(g, b))
        if (m > 1f) { // soft gamut: pull toward luminance instead of clipping a channel (keeps skin hue)
            if (yt >= 1f) { r = 1f; g = 1f; b = 1f } else {
                val s = (1f - yt) / (m - yt)
                r = yt + (r - yt) * s; g = yt + (g - yt) * s; b = yt + (b - yt) * s
            }
        }
        val er = ColorMath.encode(r); val eg = ColorMath.encode(g); val eb = ColorMath.encode(b)
        val y = ColorMath.luma(er, eg, eb)
        out[0] = y; out[1] = ColorMath.cb(eb, y); out[2] = ColorMath.cr(er, y)
    }

    /** Colour grade in Y'CbCr, written as encoded RGB into [out]. */
    fun grade(y: Float, cb: Float, cr: Float, out: FloatArray) {
        val c = sqrt(cb * cb + cr * cr)
        var ncb = cb; var ncr = cr
        if (c > 1e-5f) {
            var hue = Math.toDegrees(atan2(cr, cb).toDouble()).toFloat()
            if (hue < 0f) hue += 360f
            val skin = band(hue, 125f, 12f, 30f) * ColorMath.smoothstep(0.015f, 0.04f, c) *
                (1f - ColorMath.smoothstep(0.22f, 0.3f, c)) * ColorMath.smoothstep(0.08f, 0.18f, y)
            // Near-neutral pixels (c < ~0.03) are greys: boosting them would re-introduce casts and chroma noise.
            val colourful = ColorMath.smoothstep(0.01f, 0.04f, c)
            var gain = p.saturation * colourful + p.vibrance * colourful * (1f - ColorMath.smoothstep(0.04f, 0.25f, c)) +
                p.reds * band(hue, 100f, 12f, 28f) +
                p.golds * band(hue, 160f, 10f, 24f) +
                p.greens * band(hue, 220f, 25f, 45f) +
                p.blues * band(hue, 335f, 20f, 40f)
            gain *= 1f - p.skinProtect * skin
            // No colour boosting in near-black / near-white (that is where noise and blown areas live).
            if (gain > 0f) gain *= ColorMath.smoothstep(0.03f, 0.12f, y) * (1f - ColorMath.smoothstep(0.92f, 1f, y))
            var nc = c * (1f + gain)
            if (gain > 0f && nc > 0.28f) nc = max(c, 0.28f + (nc - 0.28f) * 0.3f) // saturation ceiling
            val s = nc / c
            ncb = cb * s; ncr = cr * s
        }
        ColorMath.ycc2rgb(y, ncb, ncr, out)
        // Out-of-gamut after grading: desaturate toward luma rather than clip channels.
        val mx = max(out[0], max(out[1], out[2])); val mn = min(out[0], min(out[1], out[2]))
        var t = 1f
        if (mx > 1f) t = min(t, (1f - y) / (mx - y))
        if (mn < 0f) t = min(t, y / (y - mn))
        if (t < 1f) for (i in 0..2) out[i] = y + (out[i] - y) * t.coerceAtLeast(0f)
    }

    private fun band(hue: Float, center: Float, inner: Float, outer: Float): Float {
        var d = abs(hue - center)
        if (d > 180f) d = 360f - d
        return 1f - ColorMath.smoothstep(inner, outer, d)
    }
}
