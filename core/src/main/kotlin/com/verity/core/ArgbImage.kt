package com.verity.core

/**
 * Packed 8-bit ARGB image (the same layout as android.graphics.Bitmap.getPixels /
 * java.awt.image.BufferedImage.getRGB), so the pipeline runs unchanged on Android and the JVM.
 */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "empty image" }
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    val longEdge: Int get() = maxOf(width, height)

    fun crop(rect: Rect): ArgbImage {
        val out = ArgbImage(rect.width, rect.height)
        for (y in 0 until rect.height) {
            System.arraycopy(pixels, (rect.top + y) * width + rect.left, out.pixels, y * rect.width, rect.width)
        }
        return out
    }
}

/** Integer pixel rectangle, [left, right) x [top, bottom). */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/** Normalised rectangle in [0,1] image coordinates (used by face detectors and the AI director). */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun toPixels(w: Int, h: Int) = Rect(
        (left * w).toInt().coerceIn(0, w - 1), (top * h).toInt().coerceIn(0, h - 1),
        (right * w).toInt().coerceIn(1, w), (bottom * h).toInt().coerceIn(1, h),
    )
}
