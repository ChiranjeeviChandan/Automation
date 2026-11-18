package com.verity.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Square 1:1 framing for WhatsApp / social avatars, which are displayed through a circular mask.
 * The subject box is expanded to head-and-shoulders, then the square is sized so the whole expanded
 * box fits inside the inscribed circle (its diagonal <= the square side), and centred on the subject.
 */
object ProfileCrop {

    fun squareFor(w: Int, h: Int, subject: Rect): Rect {
        val maxSide = min(w, h)
        // Faces: grow sideways for hair/ears, downward for shoulders, a little upward for hair.
        val ew = subject.width * 1.6f
        val eh = subject.height * 2.0f
        val cx = subject.centerX
        val cy = subject.centerY + subject.height * 0.25f
        val side = min(maxSide, max(hypot(ew, eh).roundToInt(), 16))
        val left = (cx - side / 2f).roundToInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).roundToInt().coerceIn(0, h - side)
        return Rect(left, top, left + side, top + side)
    }

    /** True if every corner of [box] lies inside the circle inscribed in square [sq]. */
    fun fitsInCircle(sq: Rect, box: Rect): Boolean {
        val r = sq.width / 2f
        return listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
            .all { (x, y) -> hypot(x - sq.centerX, y - sq.centerY) <= r + 1f }
    }
}
