package com.verity.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Binary selection over an image. true = "remove this pixel". */
class Mask(val width: Int, val height: Int, val bits: BooleanArray = BooleanArray(width * height)) {
    init { require(bits.size == width * height) }

    fun count() = bits.count { it }
    fun fraction() = count().toFloat() / bits.size
    fun isEmpty() = bits.none { it }

    fun bbox(): Rect? {
        var l = width; var t = height; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) if (bits[y * width + x]) {
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, b + 1)
    }

    /** Paint (or erase) a filled disc. */
    fun disc(cx: Float, cy: Float, radius: Float, value: Boolean = true) {
        val x0 = max(0, (cx - radius).toInt()); val x1 = min(width - 1, (cx + radius).toInt() + 1)
        val y0 = max(0, (cy - radius).toInt()); val y1 = min(height - 1, (cy + radius).toInt() + 1)
        val r2 = radius * radius
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x - cx; val dy = y - cy
            if (dx * dx + dy * dy <= r2) bits[y * width + x] = value
        }
    }

    /** Paint a brush stroke through [points] (pixel coordinates). */
    fun stroke(points: List<Pair<Float, Float>>, radius: Float, value: Boolean = true) {
        if (points.isEmpty()) return
        disc(points[0].first, points[0].second, radius, value)
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]; val (bx, by) = points[i]
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = max(1, (len / max(1f, radius * 0.35f)).roundToInt())
            for (s in 1..steps) { val t = s.toFloat() / steps; disc(ax + (bx - ax) * t, ay + (by - ay) * t, radius, value) }
        }
    }

    fun rect(r: Rect, value: Boolean = true) {
        for (y in max(0, r.top) until min(height, r.bottom)) for (x in max(0, r.left) until min(width, r.right)) bits[y * width + x] = value
    }

    fun or(other: Mask): Mask { require(other.width == width && other.height == height); return Mask(width, height, BooleanArray(bits.size) { bits[it] || other.bits[it] }) }

    /** Grow the selection by [r] pixels (Euclidean-ish, via a two-pass chamfer distance transform). */
    fun dilated(r: Float): Mask {
        if (r <= 0f) return Mask(width, height, bits.copyOf())
        val inf = 1e9f
        val d = FloatArray(bits.size) { if (bits[it]) 0f else inf }
        val a = 1f; val b = 1.4142f
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * width + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + a)
            if (y > 0) {
                v = min(v, d[i - width] + a)
                if (x > 0) v = min(v, d[i - width - 1] + b)
                if (x < width - 1) v = min(v, d[i - width + 1] + b)
            }
            d[i] = v
        }
        for (y in height - 1 downTo 0) for (x in width - 1 downTo 0) {
            val i = y * width + x
            var v = d[i]
            if (x < width - 1) v = min(v, d[i + 1] + a)
            if (y < height - 1) {
                v = min(v, d[i + width] + a)
                if (x < width - 1) v = min(v, d[i + width + 1] + b)
                if (x > 0) v = min(v, d[i + width - 1] + b)
            }
            d[i] = v
        }
        return Mask(width, height, BooleanArray(bits.size) { d[it] <= r })
    }

    companion object {
        fun ofNormRect(w: Int, h: Int, r: NormRect) = Mask(w, h).also { it.rect(r.toPixels(w, h)) }
    }
}
