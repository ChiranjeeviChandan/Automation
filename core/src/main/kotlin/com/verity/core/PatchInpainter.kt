package com.verity.core

import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Exemplar-based image completion: multi-scale PatchMatch (Barnes et al. 2009) with
 * expectation-maximisation voting (Wexler et al. 2007). The hole is rebuilt only from patches that
 * exist elsewhere in the same photograph, so the fill uses the scene's own grass, wall, sky or sand
 * instead of texture invented by a model.
 *
 * Pixels outside the hole are never written.
 */
object PatchInpainter {
    private const val R = 3 // 7x7 patches
    private const val P = 2 * R + 1

    /**
     * @param hole true = unknown pixel to synthesise.
     * @param guide optional full-size image whose hole pixels hold a coarse structural fill (e.g. from a
     *   neural inpainter). It seeds the coarsest level; PatchMatch then replaces it with real texture.
     */
    fun fill(img: ArgbImage, hole: BooleanArray, guide: ArgbImage? = null): ArgbImage {
        require(hole.size == img.pixels.size)
        if (hole.none { it }) return img
        // Build the pyramid, finest first.
        val levels = ArrayList<Level>()
        levels += Level.from(img, hole)
        while (true) {
            val l = levels.last()
            if (min(l.w, l.h) / 2 < 4 * P) break
            val holeSpan = l.holeSpan()
            if (holeSpan <= 2 * P) break
            val next = l.down()
            if (next.validCount() < next.holeCount() + 16) break // not enough exemplars left
            levels += next
        }
        // Dominant self-similarity offsets of the photo (He & Sun 2012), measured at a mid level and
        // offered to PatchMatch everywhere: repeating structure (bricks, tiles, fences, windows) is
        // then continued coherently instead of being averaged into mush.
        val probe = levels[min(1, levels.lastIndex)]
        val probeScale = if (probe === levels[0]) 1 else 2
        val offsets = probe.dominantOffsets(8).map { (dx, dy) -> dx * probeScale to dy * probeScale }
        // Coarsest: initial guess.
        val coarse = levels.last()
        if (guide != null) coarse.seedFrom(Level.from(guide, hole).downTo(coarse.w, coarse.h)) else coarse.onionPeel()
        coarse.offsets = scaleOffsets(offsets, levels.lastIndex)
        var nnf = coarse.offsetNnf()
        for (li in levels.indices.reversed()) {
            val lv = levels[li]
            lv.offsets = scaleOffsets(offsets, li)
            if (li != levels.lastIndex) {
                lv.upsampleFrom(levels[li + 1])
                nnf = lv.upsampleNnf(levels[li + 1], nnf)
            }
            val em = when {
                li == levels.lastIndex -> 12
                li == 0 -> if (lv.holeCount() > 150_000) 2 else 3 // coarse levels already fixed the layout
                else -> 6
            }
            val dist = FloatArray(lv.n)
            for (e in 0 until em) {
                lv.computeDistances(nnf, dist)
                for (it in 0 until 4) nnf = lv.patchMatchIteration(nnf, dist, seed = li * 1000 + e * 10 + it)
                lv.vote(nnf, dist, sharp = li == 0 && e == em - 1)
            }
        }
        return levels[0].writeBack(img)
    }

    private fun scaleOffsets(o: List<Pair<Int, Int>>, level: Int): List<Pair<Int, Int>> {
        val f = 1 shl level
        return o.map { (dx, dy) -> Math.round(dx.toFloat() / f) to Math.round(dy.toFloat() / f) }
            .filter { (dx, dy) -> dx != 0 || dy != 0 }.distinct()
    }

    /** One pyramid level. Colours are planar float RGB on the 0..255 scale. */
    private class Level(val w: Int, val h: Int, val c: FloatArray, val hole: BooleanArray) {
        val n = w * h
        /** Centres whose full patch is in-bounds and fully known: allowed sources. */
        val valid = BooleanArray(n)
        val validList: IntArray
        /** Centres whose patch touches the hole: they get a nearest-neighbour field entry. */
        val isTarget = BooleanArray(n)
        val targets: IntArray

        init {
            // prefix sums of hole for O(1) "patch contains hole" queries
            val ps = IntArray((w + 1) * (h + 1))
            for (y in 0 until h) for (x in 0 until w) {
                ps[(y + 1) * (w + 1) + x + 1] = (if (hole[y * w + x]) 1 else 0) + ps[y * (w + 1) + x + 1] + ps[(y + 1) * (w + 1) + x] - ps[y * (w + 1) + x]
            }
            fun holesIn(x0: Int, y0: Int, x1: Int, y1: Int) = ps[y1 * (w + 1) + x1] - ps[y0 * (w + 1) + x1] - ps[y1 * (w + 1) + x0] + ps[y0 * (w + 1) + x0]
            val vl = ArrayList<Int>(); val tl = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val x0 = max(0, x - R); val y0 = max(0, y - R); val x1 = min(w, x + R + 1); val y1 = min(h, y + R + 1)
                val hc = holesIn(x0, y0, x1, y1)
                if (hc > 0) { isTarget[i] = true; tl += i }
                if (hc == 0 && x >= R && y >= R && x < w - R && y < h - R) { valid[i] = true; vl += i }
            }
            validList = vl.toIntArray(); targets = tl.toIntArray()
        }

        var offsets: List<Pair<Int, Int>> = emptyList()

        fun validCount() = validList.size

        /**
         * Most frequent offsets between similar known patches (ignoring trivially short ones).
         * Each known patch gets a nearest neighbour by a few PatchMatch-style rounds on a sparse grid,
         * then offsets are histogrammed and the top peaks (with non-maximum suppression) kept.
         */
        fun dominantOffsets(k: Int): List<Pair<Int, Int>> {
            if (validList.size < 64) return emptyList()
            val minOff = 2 * P
            val step = max(1, kotlin.math.sqrt(validList.size / 6000.0).toInt())
            val samples = validList.filterIndexed { i, _ -> i % (step * step) == 0 }
            val counts = HashMap<Long, Int>()
            for ((si, t) in samples.withIndex()) {
                val tx = t % w; val ty = t / w
                var best = -1; var bd = Float.MAX_VALUE
                var rng = hash(t, 991)
                for (trial in 0 until 48) {
                    rng = rng * 1103515245 + 12345 and 0x7fffffff
                    val cand = validList[rng % validList.size]
                    val cx = cand % w; val cy = cand / w
                    if (kotlin.math.abs(cx - tx) < minOff && kotlin.math.abs(cy - ty) < minOff) continue
                    val d = dist(t, cand, bd)
                    if (d < bd) { bd = d; best = cand }
                }
                if (best < 0) continue
                // local refinement around the best candidate
                var rad = max(w, h) / 4
                while (rad >= 1) {
                    for (j in 0 until 4) {
                        rng = rng * 1103515245 + 12345 and 0x7fffffff
                        val cx = ((best % w) + (rng % (2 * rad + 1)) - rad).coerceIn(0, w - 1)
                        rng = rng * 1103515245 + 12345 and 0x7fffffff
                        val cy = ((best / w) + (rng % (2 * rad + 1)) - rad).coerceIn(0, h - 1)
                        val cand = cy * w + cx
                        if (!valid[cand] || (kotlin.math.abs(cx - tx) < minOff && kotlin.math.abs(cy - ty) < minOff)) continue
                        val d = dist(t, cand, bd)
                        if (d < bd) { bd = d; best = cand }
                    }
                    rad /= 2
                }
                val dx = best % w - tx; val dy = best / w - ty
                val key = (dx.toLong() shl 32) or (dy.toLong() and 0xffffffffL)
                counts[key] = (counts[key] ?: 0) + 1
                if (si > 20000) break
            }
            // Smooth the histogram over +-1 px, then greedy peak picking with suppression.
            fun c(dx: Int, dy: Int) = counts[(dx.toLong() shl 32) or (dy.toLong() and 0xffffffffL)] ?: 0
            val scored = counts.keys.map { key ->
                val dx = (key shr 32).toInt(); val dy = key.toInt()
                var sum = 0
                for (a in -1..1) for (b in -1..1) sum += c(dx + a, dy + b)
                Triple(dx, dy, sum)
            }.sortedByDescending { it.third }
            val out = ArrayList<Pair<Int, Int>>()
            for ((dx, dy, _) in scored) {
                if (out.size >= k) break
                if (out.any { (ox, oy) -> kotlin.math.abs(ox - dx) <= 2 && kotlin.math.abs(oy - dy) <= 2 }) continue
                out += dx to dy
            }
            return out
        }

        /** Initial field: for each target, the best of the dominant offsets that lands on a valid source. */
        fun offsetNnf(): IntArray {
            val f = randomNnf(seed = 1)
            if (offsets.isEmpty()) return f
            IntStream.range(0, targets.size).parallel().forEach { k ->
                val t = targets[k]
                var best = f[t]; var bd = dist(t, best, Float.MAX_VALUE)
                val tx = t % w; val ty = t / w
                for ((dx, dy) in offsets) {
                    val sx = tx + dx; val sy = ty + dy
                    if (sx !in 0 until w || sy !in 0 until h) continue
                    val s = sy * w + sx
                    if (!valid[s]) continue
                    val d = dist(t, s, bd)
                    if (d < bd) { bd = d; best = s }
                }
                f[t] = best
            }
            return f
        }
        fun holeCount() = hole.count { it }
        fun holeSpan(): Int {
            var l = w; var r = -1; var t = h; var b = -1
            for (i in 0 until n) if (hole[i]) { val x = i % w; val y = i / w; l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y) }
            return max(r - l, b - t) + 1
        }

        fun down(): Level {
            val nw = w / 2; val nh = h / 2
            val nc = FloatArray(3 * nw * nh); val nhole = BooleanArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                var cnt = 0; var r = 0f; var g = 0f; var b = 0f; var anyHole = false
                for (dy in 0..1) for (dx in 0..1) {
                    val i = (2 * y + dy) * w + 2 * x + dx
                    if (hole[i]) anyHole = true else { r += c[3 * i]; g += c[3 * i + 1]; b += c[3 * i + 2]; cnt++ }
                }
                val o = y * nw + x
                nhole[o] = anyHole
                if (cnt > 0) { nc[3 * o] = r / cnt; nc[3 * o + 1] = g / cnt; nc[3 * o + 2] = b / cnt }
            }
            return Level(nw, nh, nc, nhole)
        }

        /** Repeated 2x box downsampling of this (guide) level to exactly [tw] x [th]. */
        fun downTo(tw: Int, th: Int): Level {
            var l = this
            while (l.w > tw || l.h > th) {
                val nw = l.w / 2; val nh = l.h / 2
                val nc = FloatArray(3 * nw * nh)
                for (y in 0 until nh) for (x in 0 until nw) for (k in 0..2) {
                    nc[3 * (y * nw + x) + k] = (l.c[3 * ((2 * y) * l.w + 2 * x) + k] + l.c[3 * ((2 * y) * l.w + 2 * x + 1) + k] +
                        l.c[3 * ((2 * y + 1) * l.w + 2 * x) + k] + l.c[3 * ((2 * y + 1) * l.w + 2 * x + 1) + k]) / 4f
                }
                l = Level(nw, nh, nc, BooleanArray(nw * nh))
            }
            return l
        }

        fun seedFrom(g: Level) { for (i in 0 until n) if (hole[i]) for (k in 0..2) c[3 * i + k] = g.c[3 * i + k] }

        /** Fill the hole from its border inward with the mean of already-known 8-neighbours. */
        fun onionPeel() {
            val known = BooleanArray(n) { !hole[it] }
            var remaining = n - known.count { it }
            while (remaining > 0) {
                val front = ArrayList<Int>()
                for (i in 0 until n) if (!known[i]) {
                    val x = i % w; val y = i / w
                    var s0 = 0f; var s1 = 0f; var s2 = 0f; var cnt = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val xx = x + dx; val yy = y + dy
                        if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                        val j = yy * w + xx
                        if (known[j]) { s0 += c[3 * j]; s1 += c[3 * j + 1]; s2 += c[3 * j + 2]; cnt++ }
                    }
                    if (cnt > 0) { c[3 * i] = s0 / cnt; c[3 * i + 1] = s1 / cnt; c[3 * i + 2] = s2 / cnt; front += i }
                }
                if (front.isEmpty()) break
                for (i in front) known[i] = true
                remaining -= front.size
            }
        }

        fun upsampleFrom(coarse: Level) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!hole[i]) continue
                val cx = min(coarse.w - 1, x / 2); val cy = min(coarse.h - 1, y / 2)
                val j = cy * coarse.w + cx
                for (k in 0..2) c[3 * i + k] = coarse.c[3 * j + k]
            }
        }

        fun randomNnf(seed: Int): IntArray {
            val f = IntArray(n) { -1 }
            for (t in targets) f[t] = validList[(hash(t, seed) % validList.size)]
            return f
        }

        fun upsampleNnf(coarse: Level, cn: IntArray): IntArray {
            val f = IntArray(n) { -1 }
            for (t in targets) {
                val x = t % w; val y = t / w
                val cx = min(coarse.w - 1, x / 2); val cy = min(coarse.h - 1, y / 2)
                val cs = cn[cy * coarse.w + cx]
                var cand = -1
                if (cs >= 0) {
                    val sx = (cs % coarse.w) * 2 + (x and 1); val sy = (cs / coarse.w) * 2 + (y and 1)
                    if (sx in 0 until w && sy in 0 until h && valid[sy * w + sx]) cand = sy * w + sx
                }
                f[t] = if (cand >= 0) cand else validList[hash(t, 77) % validList.size]
            }
            return f
        }

        fun dist(t: Int, s: Int, best: Float): Float {
            val tx = t % w; val ty = t / w; val sx = s % w; val sy = s / w
            var sum = 0f
            for (dy in -R..R) {
                val ty2 = ty + dy
                if (ty2 < 0 || ty2 >= h) continue
                val trow = ty2 * w; val srow = (sy + dy) * w
                for (dx in -R..R) {
                    val tx2 = tx + dx
                    if (tx2 < 0 || tx2 >= w) continue
                    val a = 3 * (trow + tx2); val b = 3 * (srow + sx + dx)
                    val d0 = c[a] - c[b]; val d1 = c[a + 1] - c[b + 1]; val d2 = c[a + 2] - c[b + 2]
                    sum += d0 * d0 + d1 * d1 + d2 * d2
                }
                if (sum >= best) return sum
            }
            return sum
        }

        fun computeDistances(f: IntArray, d: FloatArray) {
            IntStream.range(0, targets.size).parallel().forEach { k -> val t = targets[k]; d[t] = dist(t, f[t], Float.MAX_VALUE) }
        }

        /** Jacobi-style PatchMatch step (propagation from the previous field + random search): parallel-safe. */
        fun patchMatchIteration(f: IntArray, d: FloatArray, seed: Int): IntArray {
            val nf = f.copyOf()
            IntStream.range(0, targets.size).parallel().forEach { k ->
                val t = targets[k]
                var best = f[t]; var bd = d[t]
                val tx = t % w; val ty = t / w
                fun tryCand(s: Int) {
                    if (s < 0 || s >= n || !valid[s] || s == best) return
                    val dd = dist(t, s, bd)
                    if (dd < bd) { bd = dd; best = s }
                }
                // propagation: neighbours' matches, shifted back by the neighbour offset
                if (tx > 0) f[t - 1].let { if (it >= 0 && it % w < w - 1) tryCand(it + 1) }
                if (tx < w - 1) f[t + 1].let { if (it >= 0 && it % w > 0) tryCand(it - 1) }
                if (ty > 0) f[t - w].let { if (it >= 0) tryCand(it + w) }
                if (ty < h - 1) f[t + w].let { if (it >= 0) tryCand(it - w) }
                // random search around the current best with exponentially shrinking radius
                var rad = max(w, h)
                var rng = hash(t, seed)
                while (rad >= 1) {
                    rng = rng * 1103515245 + 12345 and 0x7fffffff
                    val rx = (rng % (2 * rad + 1)) - rad
                    rng = rng * 1103515245 + 12345 and 0x7fffffff
                    val ry = (rng % (2 * rad + 1)) - rad
                    val sx = (best % w + rx).coerceIn(0, w - 1); val sy = (best / w + ry).coerceIn(0, h - 1)
                    tryCand(sy * w + sx)
                    rad /= 2
                }
                // dominant offsets: continue the photo's repeating structure coherently
                for ((dx, dy) in offsets) {
                    val sx = tx + dx; val sy = ty + dy
                    if (sx in 0 until w && sy in 0 until h) tryCand(sy * w + sx)
                }
                // one global jump to escape local minima
                rng = rng * 1103515245 + 12345 and 0x7fffffff
                tryCand(validList[rng % validList.size])
                nf[t] = best; d[t] = bd
            }
            return nf
        }

        /** EM voting, written as a gather (each hole pixel pulls from all patches covering it) so it is parallel-safe. */
        fun vote(f: IntArray, d: FloatArray, sharp: Boolean = false) {
            // sigma^2 from the 75th percentile of patch distances (Wexler et al.). The final pass additionally
            // favours the single best-matching patch: crisp texture instead of an average.
            val sample = FloatArray(min(targets.size, 4096)) { d[targets[(it.toLong() * targets.size / min(targets.size, 4096)).toInt()]] }
            sample.sort()
            val sig2 = max(1f, sample[(sample.size * 3) / 4])
            val out = FloatArray(3 * n)
            IntStream.range(0, h).parallel().forEach { y ->
                for (x in 0 until w) {
                    val p = y * w + x
                    if (!hole[p]) continue
                    var a0 = 0f; var a1 = 0f; var a2 = 0f; var ws = 0f
                    var bestD = Float.MAX_VALUE; var bestQ = -1
                    for (dy in -R..R) {
                        val ty = y - dy
                        if (ty < 0 || ty >= h) continue
                        for (dx in -R..R) {
                            val tx = x - dx
                            if (tx < 0 || tx >= w) continue
                            val t = ty * w + tx
                            val s = f[t]
                            if (s < 0) continue
                            val wt = exp(-d[t] / (2f * sig2))
                            val q = 3 * ((s / w + dy) * w + (s % w + dx))
                            if (d[t] < bestD) { bestD = d[t]; bestQ = q }
                            a0 += wt * c[q]; a1 += wt * c[q + 1]; a2 += wt * c[q + 2]; ws += wt
                        }
                    }
                    if (sharp && bestQ >= 0) {
                        // Final pass: blend 70% of the single best-matching patch with 30% of the weighted
                        // mean - keeps real grain while hiding seams between copied regions.
                        val m = if (ws > 0f) 1f / ws else 0f
                        out[3 * p] = 0.7f * c[bestQ] + 0.3f * (if (ws > 0f) a0 * m else c[bestQ])
                        out[3 * p + 1] = 0.7f * c[bestQ + 1] + 0.3f * (if (ws > 0f) a1 * m else c[bestQ + 1])
                        out[3 * p + 2] = 0.7f * c[bestQ + 2] + 0.3f * (if (ws > 0f) a2 * m else c[bestQ + 2])
                    } else if (ws > 0f) { out[3 * p] = a0 / ws; out[3 * p + 1] = a1 / ws; out[3 * p + 2] = a2 / ws }
                    else { out[3 * p] = c[3 * p]; out[3 * p + 1] = c[3 * p + 1]; out[3 * p + 2] = c[3 * p + 2] }
                }
            }
            for (p in 0 until n) if (hole[p]) { c[3 * p] = out[3 * p]; c[3 * p + 1] = out[3 * p + 1]; c[3 * p + 2] = out[3 * p + 2] }
        }

        fun writeBack(src: ArgbImage): ArgbImage {
            val out = ArgbImage(w, h, src.pixels.copyOf())
            for (i in 0 until n) if (hole[i]) {
                out.pixels[i] = (src.pixels[i] and -0x1000000) or (c8(c[3 * i]) shl 16) or (c8(c[3 * i + 1]) shl 8) or c8(c[3 * i + 2])
            }
            return out
        }

        private fun c8(v: Float): Int { val i = (v + 0.5f).toInt(); return if (i < 0) 0 else if (i > 255) 255 else i }

        companion object {
            fun from(img: ArgbImage, hole: BooleanArray): Level {
                val c = FloatArray(3 * img.pixels.size)
                for (i in img.pixels.indices) {
                    val p = img.pixels[i]
                    c[3 * i] = ((p shr 16) and 0xFF).toFloat(); c[3 * i + 1] = ((p shr 8) and 0xFF).toFloat(); c[3 * i + 2] = (p and 0xFF).toFloat()
                }
                return Level(img.width, img.height, c, hole.copyOf())
            }

            fun hash(a: Int, b: Int): Int {
                var x = a * 0x27d4eb2d xor (b * 0x165667b1)
                x = x xor (x ushr 15); x *= 0x2c1b3c6d; x = x xor (x ushr 12); x *= 0x297a2d39; x = x xor (x ushr 15)
                return x and 0x7fffffff
            }
        }
    }
}
