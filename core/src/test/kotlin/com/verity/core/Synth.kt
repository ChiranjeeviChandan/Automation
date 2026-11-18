package com.verity.core

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Deterministic synthetic test scene: grey ramp, grey card, skin patch, foliage, sky, noise, a cast. */
object Synth {
    data class Patch(val name: String, val rect: Rect, val rgb: Triple<Int, Int, Int>)

    val W = 960
    val H = 640
    val patches = listOf(
        Patch("grey", Rect(60, 60, 220, 220), Triple(118, 118, 118)),
        Patch("skin", Rect(300, 60, 460, 220), Triple(214, 160, 130)),
        Patch("darkSkin", Rect(300, 260, 460, 420), Triple(120, 80, 60)),
        Patch("foliage", Rect(540, 60, 700, 220), Triple(70, 110, 50)),
        Patch("sky", Rect(740, 60, 900, 220), Triple(120, 160, 215)),
        Patch("red", Rect(540, 260, 700, 420), Triple(180, 40, 40)),
    )

    /** [cast] multiplies channels (e.g. tungsten = r up, b down). */
    fun scene(cast: FloatArray = floatArrayOf(1f, 1f, 1f), noise: Float = 3f, seed: Int = 7): ArgbImage {
        val img = ArgbImage(W, H)
        val rnd = Random(seed)
        for (y in 0 until H) for (x in 0 until W) {
            var r: Int; var g: Int; var b: Int
            val v = 20 + (x * 215 / W)
            r = v; g = v; b = v
            // a hard-edged diagonal bar to test structure preservation
            if (y in 470..600 && (x / 40) % 2 == 0) { r = 230; g = 230; b = 230 }
            for (p in patches) if (x >= p.rect.left && x < p.rect.right && y >= p.rect.top && y < p.rect.bottom) {
                r = p.rgb.first; g = p.rgb.second; b = p.rgb.third
            }
            fun f(c: Int, k: Float) = max(0, min(255, (c * k + rnd.nextFloat() * 2 * noise - noise).toInt()))
            img.pixels[y * W + x] = -0x1000000 or (f(r, cast[0]) shl 16) or (f(g, cast[1]) shl 8) or f(b, cast[2])
        }
        return img
    }

    fun mean(img: ArgbImage, rect: Rect): FloatArray {
        // sample the centre of the patch to avoid edge effects
        val inset = Rect(rect.left + rect.width / 4, rect.top + rect.height / 4, rect.right - rect.width / 4, rect.bottom - rect.height / 4)
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        for (y in inset.top until inset.bottom) for (x in inset.left until inset.right) {
            val p = img.pixels[y * img.width + x]
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
        }
        return floatArrayOf((r / n).toFloat(), (g / n).toFloat(), (b / n).toFloat())
    }
}
