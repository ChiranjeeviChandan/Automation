package com.verity.core

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemovalTest {

    /** Brick-like periodic texture + gradient + grain: something with real structure to continue. */
    private fun background(w: Int, h: Int, seed: Int = 3): ArgbImage {
        val img = ArgbImage(w, h)
        val rnd = Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            val row = y / 24
            val off = if (row % 2 == 0) 0 else 24
            val mortar = (y % 24 < 3) || ((x + off) % 48 < 3)
            val base = if (mortar) 200 else 150 + (40 * sin((x + off) / 48 * 1.7 + row)).toInt()
            val n = rnd.nextInt(-6, 7)
            val r = (base + 30 + n + y / 12).coerceIn(0, 255)
            val g = (base - 30 + n).coerceIn(0, 255)
            val b = (base - 60 + n).coerceIn(0, 255)
            img.pixels[y * w + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        return img
    }

    private fun withObject(bg: ArgbImage, obj: Rect): ArgbImage {
        val o = ArgbImage(bg.width, bg.height, bg.pixels.copyOf())
        for (y in obj.top until obj.bottom) for (x in obj.left until obj.right) o.pixels[y * o.width + x] = -0x1000000 or (20 shl 16) or (60 shl 8) or 220
        return o
    }

    private fun mae(a: ArgbImage, b: ArgbImage, m: Mask): Double {
        var s = 0L; var n = 0
        for (i in a.pixels.indices) if (m.bits[i]) for (sh in intArrayOf(0, 8, 16)) { s += abs(((a.pixels[i] shr sh) and 0xFF) - ((b.pixels[i] shr sh) and 0xFF)); n++ }
        return s.toDouble() / n
    }

    @Test fun removesObjectAndRecoversTexture() {
        val bg = background(480, 360)
        val obj = Rect(200, 140, 280, 240)
        val photo = withObject(bg, obj)
        val sel = Mask(480, 360).also { it.rect(obj) }
        val t0 = System.nanoTime()
        val res = ObjectRemover.remove(photo, sel)
        val ms = (System.nanoTime() - t0) / 1e6
        val holeMae = mae(res.image, bg, res.filled)
        val objMae = mae(photo, bg, res.filled)
        // Baseline: the best possible flat fill (mean colour of the background inside the hole).
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (i in bg.pixels.indices) if (res.filled.bits[i]) { val p = bg.pixels[i]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++ }
        val flat = ArgbImage(480, 360, photo.pixels.copyOf()).also { f ->
            val c = -0x1000000 or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            for (i in f.pixels.indices) if (res.filled.bits[i]) f.pixels[i] = c
        }
        val flatMae = mae(flat, bg, res.filled)
        println("removal: hole MAE=%.1f (object=%.1f, ideal flat fill=%.1f) in %.0f ms".format(holeMae, objMae, flatMae, ms))
        assertTrue(holeMae < objMae * 0.35, "object not removed")
        assertTrue(holeMae < flatMae, "texture not better than a flat fill")
        save(photo, "remove_before.png"); save(res.image, "remove_after.png")
    }

    @Test fun pixelsOutsideTheSelectionAreBitIdentical() {
        val photo = withObject(background(320, 240), Rect(100, 80, 160, 150))
        val sel = Mask(320, 240).also { it.rect(Rect(100, 80, 160, 150)) }
        val res = ObjectRemover.remove(photo, sel)
        for (i in photo.pixels.indices) if (!res.filled.bits[i]) assertEquals(photo.pixels[i], res.image.pixels[i])
        assertTrue(res.filled.count() > sel.count()) // grown to swallow edges
    }

    @Test fun neuralGuideIsUsedWhenProvidedAndStillOnlyTouchesTheHole() {
        val bg = background(400, 300)
        val obj = Rect(150, 100, 230, 200)
        val photo = withObject(bg, obj)
        val sel = Mask(400, 300).also { it.rect(obj) }
        var calls = 0
        // Fake model: returns the crop with the hole blurred grey. Plumbing test, not a quality test.
        val fake = NeuralFill { crop, hole -> calls++; ArgbImage(crop.width, crop.height, IntArray(crop.pixels.size) { if (hole[it]) -0x1000000 or 0x968c78 else crop.pixels[it] }) }
        val res = ObjectRemover.remove(photo, sel, fake, neuralSize = 128)
        assertEquals(1, calls); assertTrue(res.usedNeural)
        for (i in photo.pixels.indices) if (!res.filled.bits[i]) assertEquals(photo.pixels[i], res.image.pixels[i])
        assertTrue(mae(res.image, bg, res.filled) < mae(photo, bg, res.filled) * 0.4)
    }

    @Test fun brushStrokeAndDilationBehave() {
        val m = Mask(100, 100)
        m.stroke(listOf(10f to 10f, 90f to 90f), 4f)
        assertTrue(m.bits[50 * 100 + 50] && !m.bits[10 * 100 + 90])
        val d = Mask(50, 50).also { it.bits[25 * 50 + 25] = true }.dilated(5f)
        assertTrue(d.bits[25 * 50 + 30] && !d.bits[25 * 50 + 32] && d.count() in 70..100)
    }

    private fun save(i: ArgbImage, n: String) {
        val dir = File("build/visual").apply { mkdirs() }
        val bi = BufferedImage(i.width, i.height, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, i.width, i.height, i.pixels, 0, i.width)
        ImageIO.write(bi, "png", File(dir, n))
    }
}

class RemovalPerfTest {
    @Test fun twelveMegapixelFrameWithPersonSizedHole() {
        val w = 4000; val h = 3000
        val img = ArgbImage(w, h)
        val rnd = kotlin.random.Random(5)
        for (y in 0 until h) for (x in 0 until w) {
            val g = (90 + 40 * kotlin.math.sin(x / 9.0) * kotlin.math.cos(y / 13.0) + rnd.nextInt(-20, 21)).toInt().coerceIn(0, 255)
            img.pixels[y * w + x] = -0x1000000 or ((g / 2) shl 16) or (g shl 8) or (g / 3)
        }
        val sel = Mask(w, h).also { it.rect(Rect(1800, 1000, 2200, 1900)) } // ~ a standing person at mid distance
        val t0 = System.nanoTime()
        val res = ObjectRemover.remove(img, sel)
        val s = (System.nanoTime() - t0) / 1e9
        println("12 MP, 400x900 hole: %.1f s on %d JVM cores".format(s, Runtime.getRuntime().availableProcessors()))
        assertTrue(res.filled.count() >= sel.count())
    }
}
