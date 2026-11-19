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
        val s = SceneAnalyzer.analyze(img, hasFaces = faces > 0)
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
            // Contract: portrait grading may warm and enrich skin a little (the "premium portrait" look),
            // but never shift it far, wash it out, or push it toward grey or orange.
            assertTrue(dh < 6f, "$name hue moved $dh deg")
            assertTrue(cr in 0.95f..1.4f, "$name chroma ratio $cr")
        }
    }

    /** Regression: a selfie dominated by warm skin and a yellow curtain must not be "corrected" to ashy skin. */
    @Test fun warmSelfieKeepsSkinWarm() {
        val w = 600; val h = 800
        val img = ArgbImage(w, h)
        val skin = Rect(150, 200, 450, 650)
        for (y in 0 until h) for (x in 0 until w) {
            val (r, g, b) = when {
                x >= skin.left && x < skin.right && y >= skin.top && y < skin.bottom -> Triple(205, 150, 120)
                y < 300 -> Triple(215, 185, 60)   // yellow curtain
                y > 650 -> Triple(25, 25, 28)     // black vest
                else -> Triple(230, 160, 160)     // pink wall
            }
            img.pixels[y * w + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        val (out, p) = grade(img, faces = 1)
        val a = Synth.mean(img, skin); val b = Synth.mean(out, skin)
        val warmBefore = a[0] / a[2]; val warmAfter = b[0] / b[2]
        println("selfie skin R/B %.3f -> %.3f, hue shift %.2f deg, wb=%.2f".format(warmBefore, warmAfter, abs(hueDeg(a) - hueDeg(b)), p.wbStrength))
        assertTrue(warmAfter > warmBefore * 0.95f, "skin cooled: $warmBefore -> $warmAfter")
        assertTrue(abs(hueDeg(a) - hueDeg(b)) < 6f)
        assertTrue(warmAfter < warmBefore * 1.3f, "skin pushed toward orange: $warmBefore -> $warmAfter")
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

class SubjectMaskTest {
    @Test fun backgroundDarkensMoreThanFaceAndNoHaloAtTheEdge() {
        val w = 400; val h = 400
        val img = ArgbImage(w, h, IntArray(w * h) { -0x1000000 or 0xE0E0E0 }) // bright wall
        val face = Rect(150, 120, 250, 260)
        for (y in face.top until face.bottom) for (x in face.left until face.right) img.pixels[y * w + x] = -0x1000000 or (190 shl 16) or (140 shl 8) or 110
        val s = SceneAnalyzer.analyze(img)
        val p = GradeParams(wbStrength = 0f, shadows = 0f, highlights = 0f, contrast = 0f, clarity = 0f, vibrance = 0f, saturation = 0f,
            chromaDenoise = 0f, lumaDenoise = 0f, sharpen = 0f, subjectEv = 0.1f, backgroundEv = -0.5f)
        val nf = NormRect(0.375f, 0.3f, 0.625f, 0.65f)
        val out = Renderer.render(img, s, p, RenderOptions(targetLongEdge = 0, subjects = listOf(nf)))
        fun l(px: Int) = (px shr 8) and 0xFF
        val wallFar = l(out.pixels[20 * w + 20]); val wallNear = l(out.pixels[190 * w + 140]); val faceC = l(out.pixels[190 * w + 200])
        println("wall far=$wallFar near=$wallNear face=$faceC (wall in=224, face in=140)")
        assertTrue(wallFar < 214, "background not burned")
        assertTrue(faceC >= 138, "face darkened")
        assertTrue(kotlin.math.abs(wallNear - wallFar) <= 6, "halo next to the subject: near=$wallNear far=$wallFar")
    }
}
