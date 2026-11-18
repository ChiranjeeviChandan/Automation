package com.verity.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A learned inpainting model (e.g. LaMa). Receives a square RGB crop and a same-size mask
 * (true = fill) and returns the completed crop at the same size.
 */
fun interface NeuralFill {
    fun fill(crop: ArgbImage, hole: BooleanArray): ArgbImage
}

data class RemovalResult(
    val image: ArgbImage,
    /** The pixels that were rebuilt (selection after dilation). Everything else is bit-identical to the input. */
    val filled: Mask,
    val usedNeural: Boolean,
)

/**
 * Object / person removal, applied only to what the user selected.
 *
 * Hybrid strategy:
 *  1. The selection is grown slightly so object edges, hair wisps and contact shadows go too.
 *  2. If a neural model is available it sees a context crop at its native size and proposes the
 *     *structure* of what is behind the object (continuing a horizon, a railing, a wall edge).
 *  3. PatchMatch then rebuilds the region at full resolution from real patches of this photo,
 *     guided by that structure. Neural models output soft, low-res texture; this step replaces it
 *     with the scene's own grain, so the fill does not look "AI-painted" at 100% zoom.
 *  4. Only selected pixels are written back.
 */
object ObjectRemover {

    fun remove(img: ArgbImage, selection: Mask, neural: NeuralFill? = null, neuralSize: Int = 512): RemovalResult {
        require(selection.width == img.width && selection.height == img.height)
        val grow = max(3f, img.longEdge * 0.004f)
        val hole = selection.dilated(grow)
        val box = hole.bbox() ?: return RemovalResult(img, hole, false)

        // Work inside a region of interest: the hole plus enough surroundings to borrow texture from.
        val margin = max(48, max(box.width, box.height))
        val roi = Rect(
            max(0, box.left - margin), max(0, box.top - margin),
            min(img.width, box.right + margin), min(img.height, box.bottom + margin),
        )
        val roiImg = img.crop(roi)
        val roiHole = BooleanArray(roi.width * roi.height) { i ->
            hole.bits[(roi.top + i / roi.width) * img.width + roi.left + i % roi.width]
        }

        var guide: ArgbImage? = null
        if (neural != null) guide = runCatching { neuralGuide(img, hole, box, roi, neural, neuralSize) }.getOrNull()

        val filledRoi = PatchInpainter.fill(roiImg, roiHole, guide)
        val out = ArgbImage(img.width, img.height, img.pixels.copyOf())
        for (y in 0 until roi.height) for (x in 0 until roi.width) {
            val i = y * roi.width + x
            if (roiHole[i]) out.pixels[(roi.top + y) * img.width + roi.left + x] = filledRoi.pixels[i]
        }
        return RemovalResult(out, hole, guide != null)
    }

    /** Runs the neural model on a square context crop and maps its answer back onto the ROI. */
    private fun neuralGuide(img: ArgbImage, hole: Mask, box: Rect, roi: Rect, neural: NeuralFill, size: Int): ArgbImage {
        // Square crop ~2.5x the hole so the model sees context, clamped to the image.
        val side = min(min(img.width, img.height), max(size / 2, (max(box.width, box.height) * 2.5f).roundToInt()))
        val cx = (box.left + box.right) / 2; val cy = (box.top + box.bottom) / 2
        val left = (cx - side / 2).coerceIn(0, img.width - side)
        val top = (cy - side / 2).coerceIn(0, img.height - side)
        val sq = Rect(left, top, left + side, top + side)
        val crop = img.crop(sq)
        val small = resizeBilinear(crop, size, size)
        val smallHole = BooleanArray(size * size) { i ->
            val sx = ((i % size + 0.5f) * side / size).toInt().coerceIn(0, side - 1)
            val sy = ((i / size + 0.5f) * side / size).toInt().coerceIn(0, side - 1)
            hole.bits[(top + sy) * img.width + left + sx]
        }
        // Grow the model's mask by a pixel so downsampling never leaves object remnants visible to it.
        val modelHole = Mask(size, size, smallHole).dilated(1.5f).bits
        val done = resizeBilinear(neural.fill(small, modelHole), side, side)

        // Guide = ROI image with hole pixels from the model where the square covers them.
        val g = img.crop(roi)
        for (y in 0 until roi.height) for (x in 0 until roi.width) {
            val ix = roi.left + x; val iy = roi.top + y
            if (!hole.bits[iy * img.width + ix]) continue
            if (ix in sq.left until sq.right && iy in sq.top until sq.bottom) {
                g.pixels[y * roi.width + x] = done.pixels[(iy - sq.top) * side + (ix - sq.left)]
            }
        }
        return g
    }

    internal fun resizeBilinear(src: ArgbImage, w: Int, h: Int): ArgbImage {
        if (w == src.width && h == src.height) return src
        val out = ArgbImage(w, h)
        val sx = src.width.toFloat() / w; val sy = src.height.toFloat() / h
        // Box-average when shrinking a lot, bilinear otherwise: good enough for a model input / guide.
        for (y in 0 until h) for (x in 0 until w) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, src.width - 1f)
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, src.height - 1f)
            val x0 = fx.toInt(); val y0 = fy.toInt()
            val x1 = min(x0 + 1, src.width - 1); val y1 = min(y0 + 1, src.height - 1)
            val tx = fx - x0; val ty = fy - y0
            var px = -0x1000000
            for (sh in intArrayOf(16, 8, 0)) {
                fun ch(xx: Int, yy: Int) = ((src.pixels[yy * src.width + xx] shr sh) and 0xFF).toFloat()
                val v = (ch(x0, y0) * (1 - tx) + ch(x1, y0) * tx) * (1 - ty) + (ch(x0, y1) * (1 - tx) + ch(x1, y1) * tx) * ty
                px = px or ((v + 0.5f).toInt().coerceIn(0, 255) shl sh)
            }
            out.pixels[y * w + x] = px
        }
        return out
    }
}
