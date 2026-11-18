package com.verity.core

import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PipelineTest {

    private fun ycc(rgb: FloatArray): Triple<Float, Float, Float> {
        val r = rgb[0] / 255f; val g = rgb[1] / 255f; val b = rgb[2] / 255f
        val y = ColorMath.luma(r, g, b)
        return Triple(y, ColorMath.cb(b, y), ColorMath.cr(r, y))
    }
    private fun hueDeg(rgb: FloatArray) = ycc(rgb).let { Math.toDegrees(atan2(it.third, it.second).toDouble()).toFloat() }
    private fun chroma(rgb: FloatArray) = ycc(rgb).let { sqrt(it.second * it.second + it.third * it.third) }
    private fun castOf(rgb: FloatArray) = maxOf(rgb[0], rgb[1], rgb[2]) / minOf(rgb[0], rgb[1], rgb[2])

    private fun grade(img: ArgbImage, faces: Int = 0, opts: RenderOptions = RenderOptions(targetLongEdge = 0)): Pair<ArgbImage, GradeParams> {
        val s = SceneAnalyzer.analyze(img)
        val p = AutoGrader.propose(s, AutoGrader.classify(s, faces))
        return Renderer.render(img, s, p, opts) to p
    }

    @Test fun toneCurveIsMonotonicAcrossTheWholeParameterBox() {
        for (bp in listOf(0f, 0.03f)) for (sh in listOf(0f, 1f)) for (hl in listOf(0f, 1f)) for (c in listOf(-0.35f, 0f, 0.35f)) {
            val curve = ToneCurve(GradeParams(blackPoint = bp, shadows = sh, highlights = hl, contrast = c))
            assertTrue(curve.isMonotonic(), "non-monotonic at bp=$bp sh=$sh hl=$hl c=$c")
        }
    }

    @Test fun geometryIsUntouchedWithoutUpscale() {
        val img = Synth.scene()
        val (out, _) = grade(img)
        assertEquals(img.width, out.width); assertEquals(img.height, out.height)
    }

    @Test fun removesTungstenCastFromGreyCard() {
        val img = Synth.scene(cast = floatArrayOf(1.18f, 1f, 0.78f))
        val before = castOf(Synth.mean(img, Synth.patches[0].rect))
        val (out, _) = grade(img)
        val after = castOf(Synth.mean(out, Synth.patches[0].rect))
        println("grey cast before=%.3f after=%.3f".format(before, after))
        assertTrue(after < 1f + (before - 1f) * 0.6f, "cast not reduced enough: $before -> $after")
    }

    @Test fun skinHueAndSaturationArePreserved() {
        val img = Synth.scene()
        val (out, _) = grade(img, faces = 1)
        for (name in listOf("skin", "darkSkin")) {
            val rect = Synth.patches.first { it.name == name }.rect
            val a = Synth.mean(img, rect); val b = Synth.mean(out, rect)
            val dh = abs(hueDeg(a) - hueDeg(b)); val cr = chroma(b) / chroma(a)
            println("$name hue shift=%.2f deg, chroma ratio=%.3f".format(dh, cr))
            assertTrue(dh < 4f, "$name hue moved $dh deg")
            assertTrue(cr in 0.85f..1.12f, "$name chroma ratio $cr")
        }
    }

    @Test fun landscapeColoursGetRicherButStayBelowTheCeiling() {
        val img = Synth.scene()
        val (out, _) = grade(img)
        val f = Synth.patches.first { it.name == "foliage" }.rect
        val before = chroma(Synth.mean(img, f)); val after = chroma(Synth.mean(out, f))
        println("foliage chroma %.3f -> %.3f".format(before, after))
        assertTrue(after > before * 1.03f, "foliage not enriched")
        var maxC = 0f
        for (p in out.pixels) { val c = chroma(floatArrayOf(((p shr 16) and 0xFF).toFloat(), ((p shr 8) and 0xFF).toFloat(), (p and 0xFF).toFloat())); if (c > maxC) maxC = c }
        val inMax = img.pixels.maxOf { p -> chroma(floatArrayOf(((p shr 16) and 0xFF).toFloat(), ((p shr 8) and 0xFF).toFloat(), (p and 0xFF).toFloat())) }
        println("max chroma in=%.3f out=%.3f".format(inMax, maxC))
        assertTrue(maxC < maxOf(inMax * 1.12f, 0.34f), "over-saturation: $maxC")
    }

    @Test fun edgesStayWhereTheyWere() {
        val img = Synth.scene(noise = 0f)
        val (out, _) = grade(img)
        // Compare horizontal gradient sign/position along a row crossing the bar pattern.
        val y = 530
        var agree = 0; var total = 0
        for (x in 1 until img.width) {
            val gi = lum(img, x, y) - lum(img, x - 1, y)
            if (abs(gi) > 20) { total++; val go = lum(out, x, y) - lum(out, x - 1, y); if (go * gi > 0 && abs(go) > 10) agree++ }
        }
        println("edge agreement $agree/$total")
        assertTrue(total > 0 && agree == total)
    }

    @Test fun nearIdentityParamsBarelyChangeTheImage() {
        val img = Synth.scene(noise = 0f)
        val s = SceneAnalyzer.analyze(img)
        val id = GradeParams(wbStrength = 0f, shadows = 0f, highlights = 1f, contrast = 0f, clarity = 0f, vibrance = 0f,
            saturation = 0f, skinProtect = 0f, chromaDenoise = 0f, lumaDenoise = 0f, sharpen = 0f)
        val out = Renderer.render(img, s, id, RenderOptions(targetLongEdge = 0))
        var sum = 0L
        for (i in img.pixels.indices) for (sh in intArrayOf(0, 8, 16)) sum += abs(((img.pixels[i] shr sh) and 0xFF) - ((out.pixels[i] shr sh) and 0xFF))
        val mad = sum.toDouble() / (img.pixels.size * 3)
        println("identity-params mean abs diff = %.3f".format(mad))
        assertTrue(mad < 6.0, "identity drift $mad")
    }

    @Test fun upscalesToFourKWithoutChangingAspect() {
        val img = Synth.scene()
        val (out, _) = grade(img, opts = RenderOptions(targetLongEdge = 3840))
        assertEquals(3840, out.width); assertEquals(2560, out.height)
    }

    @Test fun profileCropKeepsFaceInsideCircle() {
        val face = Rect(600, 300, 760, 500)
        val sq = ProfileCrop.squareFor(1600, 1200, face)
        assertEquals(sq.width, sq.height)
        assertTrue(ProfileCrop.fitsInCircle(sq, face), "face outside circle: $sq")
        // Subject at the frame edge: square clamps inside the image.
        val edge = ProfileCrop.squareFor(1600, 1200, Rect(1500, 50, 1590, 160))
        assertTrue(edge.left >= 0 && edge.right <= 1600 && edge.top >= 0 && edge.bottom <= 1200)
    }

    @Test fun writesBeforeAfterForVisualInspection() {
        val img = Synth.scene(cast = floatArrayOf(1.12f, 1f, 0.85f), noise = 6f)
        val (out, p) = grade(img, faces = 1)
        println("params: $p")
        val dir = File("build/visual").apply { mkdirs() }
        fun save(i: ArgbImage, n: String) {
            val bi = BufferedImage(i.width, i.height, BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, i.width, i.height, i.pixels, 0, i.width)
            ImageIO.write(bi, "png", File(dir, n))
        }
        save(img, "before.png"); save(out, "after.png")
    }

    private fun lum(i: ArgbImage, x: Int, y: Int): Int {
        val p = i.pixels[y * i.width + x]
        return (((p shr 16) and 0xFF) * 2126 + ((p shr 8) and 0xFF) * 7152 + (p and 0xFF) * 722) / 10000
    }
}
