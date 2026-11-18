package com.verity.inpaint

import com.verity.core.ArgbImage
import com.verity.core.Mask
import com.verity.core.ObjectRemover
import com.verity.core.Rect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnnxInpainterTest {
    private val model = File(javaClass.getResource("/toy_lama.onnx")!!.toURI()).absolutePath

    @Test fun readsSignatureAndFillsOnlyTheHole() {
        OnnxInpainter(model, threads = 1).use { inp ->
            assertEquals(64, inp.inputSize)
            val crop = ArgbImage(64, 64, IntArray(64 * 64) { -0x1000000 or 0x204060 })
            val hole = BooleanArray(64 * 64) { (it % 64) in 20..40 && (it / 64) in 20..40 }
            val out = inp.fill(crop, hole)
            for (i in hole.indices) {
                if (hole[i]) assertEquals(-0x1000000 or 0x808080, out.pixels[i]) // 0.5 * 255 rounded
                else assertEquals(crop.pixels[i], out.pixels[i])
            }
        }
    }

    @Test fun plugsIntoObjectRemover() {
        val w = 300; val h = 200
        val img = ArgbImage(w, h, IntArray(w * h) { i -> val v = 60 + (i % w) / 3; -0x1000000 or (v shl 16) or (v shl 8) or v })
        val obj = Rect(120, 70, 170, 130)
        for (y in obj.top until obj.bottom) for (x in obj.left until obj.right) img.pixels[y * w + x] = -0x1000000 or 0xFF0000
        val sel = Mask(w, h).also { it.rect(obj) }
        OnnxInpainter(model, threads = 1).use { inp ->
            val res = ObjectRemover.remove(img, sel, inp, inp.inputSize)
            assertTrue(res.usedNeural)
            var red = 0
            for (i in res.image.pixels.indices) if (res.filled.bits[i] && (res.image.pixels[i] shr 16 and 0xFF) > 200 && (res.image.pixels[i] and 0xFF) < 60) red++
            assertEquals(0, red, "object pixels survived")
        }
    }
}
