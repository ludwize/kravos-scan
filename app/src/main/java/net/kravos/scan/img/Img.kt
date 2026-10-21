package net.kravos.scan.img

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plain ARGB raster. All image processing in the app works on this (no Android types),
 * so it can be unit-tested on the JVM in CI.
 */
class Img(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init { require(px.size == w * h) }
    fun copy() = Img(w, h, px.copyOf())
}

internal inline fun r(c: Int) = (c shr 16) and 0xFF
internal inline fun g(c: Int) = (c shr 8) and 0xFF
internal inline fun b(c: Int) = c and 0xFF
internal inline fun rgb(r: Int, g: Int, b: Int) =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
internal inline fun luma(c: Int) = (r(c) * 77 + g(c) * 150 + b(c) * 29) shr 8

enum class Filter(val label: String) {
    ORIGINAL("Original"),
    AUTO("Auto"),
    MAGIC("Magija"),
    GRAY("Sivo"),
    BW("Crno-belo"),
    PHOTO("Foto"),
}

object Ops {

    // ---------------------------------------------------------------- geometry

    fun rotate(src: Img, deg: Int): Img {
        val d = ((deg % 360) + 360) % 360
        if (d == 0) return src
        val w = src.w; val h = src.h; val s = src.px
        return when (d) {
            90 -> Img(h, w).also { o -> for (y in 0 until h) for (x in 0 until w) o.px[x * h + (h - 1 - y)] = s[y * w + x] }
            180 -> Img(w, h).also { o -> for (i in s.indices) o.px[s.size - 1 - i] = s[i] }
            270 -> Img(h, w).also { o -> for (y in 0 until h) for (x in 0 until w) o.px[(w - 1 - x) * h + y] = s[y * w + x] }
            else -> src
        }
    }

    /** Bilinear resize. */
    fun resize(src: Img, nw: Int, nh: Int): Img {
        if (nw == src.w && nh == src.h) return src
        val o = Img(nw, nh)
        // box-average first when shrinking a lot, so downscaled images don't alias
        if (src.w >= nw * 2 && src.h >= nh * 2) {
            val fx = src.w / nw; val fy = src.h / nh
            val half = boxShrink(src, fx, fy)
            return resize(half, nw, nh)
        }
        val sx = src.w.toFloat() / nw; val sy = src.h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (src.h - 1).toFloat())
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (src.w - 1).toFloat())
                o.px[y * nw + x] = sample(src, fx, fy)
            }
        }
        return o
    }

    private fun boxShrink(src: Img, fx: Int, fy: Int): Img {
        val nw = src.w / fx; val nh = src.h / fy
        val o = Img(nw, nh); val n = fx * fy
        for (y in 0 until nh) for (x in 0 until nw) {
            var rr = 0; var gg = 0; var bb = 0
            for (j in 0 until fy) {
                val row = (y * fy + j) * src.w + x * fx
                for (i in 0 until fx) { val c = src.px[row + i]; rr += r(c); gg += g(c); bb += b(c) }
            }
            o.px[y * nw + x] = rgb(rr / n, gg / n, bb / n)
        }
        return o
    }

    fun fitMax(src: Img, maxSide: Int): Img {
        val m = max(src.w, src.h)
        if (m <= maxSide) return src
        val s = maxSide.toFloat() / m
        return resize(src, max(1, (src.w * s).roundToInt()), max(1, (src.h * s).roundToInt()))
    }

    internal fun sample(src: Img, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, src.w - 1); val y0 = fy.toInt().coerceIn(0, src.h - 1)
        val x1 = min(x0 + 1, src.w - 1); val y1 = min(y0 + 1, src.h - 1)
        val ax = fx - x0; val ay = fy - y0
        val c00 = src.px[y0 * src.w + x0]; val c10 = src.px[y0 * src.w + x1]
        val c01 = src.px[y1 * src.w + x0]; val c11 = src.px[y1 * src.w + x1]
        fun mix(a: Int, b: Int, c: Int, d: Int): Int {
            val top = a + (b - a) * ax; val bot = c + (d - c) * ax
            return (top + (bot - top) * ay + 0.5f).toInt()
        }
        return rgb(mix(r(c00), r(c10), r(c01), r(c11)), mix(g(c00), g(c10), g(c01), g(c11)), mix(b(c00), b(c10), b(c01), b(c11)))
    }

    /**
     * Perspective-correct the quadrilateral [q] (x0,y0, x1,y1, x2,y2, x3,y3 in pixels: TL, TR, BR, BL)
     * into a rectangle. Output size follows the quad's edge lengths.
     */
    fun warp(src: Img, q: FloatArray): Img {
        fun d(i: Int, j: Int) = sqrt(((q[2 * i] - q[2 * j]) * (q[2 * i] - q[2 * j]) + (q[2 * i + 1] - q[2 * j + 1]) * (q[2 * i + 1] - q[2 * j + 1])).toDouble())
        val ow = max(d(0, 1), d(3, 2)).roundToInt().coerceIn(16, 12000)
        val oh = max(d(0, 3), d(1, 2)).roundToInt().coerceIn(16, 12000)
        val hm = homography(
            floatArrayOf(0f, 0f, ow.toFloat(), 0f, ow.toFloat(), oh.toFloat(), 0f, oh.toFloat()), q,
        )
        val o = Img(ow, oh)
        for (y in 0 until oh) {
            val yy = y + 0.5
            for (x in 0 until ow) {
                val xx = x + 0.5
                val z = hm[6] * xx + hm[7] * yy + 1.0
                val sx = ((hm[0] * xx + hm[1] * yy + hm[2]) / z - 0.5).toFloat()
                val sy = ((hm[3] * xx + hm[4] * yy + hm[5]) / z - 0.5).toFloat()
                o.px[y * ow + x] = sample(src, sx.coerceIn(0f, src.w - 1f), sy.coerceIn(0f, src.h - 1f))
            }
        }
        return o
    }

    /** 3x3 homography (h8 = 1) mapping the 4 points [from] onto [to]. */
    fun homography(from: FloatArray, to: FloatArray): DoubleArray {
        val a = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = from[2 * i].toDouble(); val y = from[2 * i + 1].toDouble()
            val u = to[2 * i].toDouble(); val v = to[2 * i + 1].toDouble()
            a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        // Gaussian elimination with partial pivoting
        for (c in 0 until 8) {
            var p = c
            for (rr in c + 1 until 8) if (abs(a[rr][c]) > abs(a[p][c])) p = rr
            val t = a[c]; a[c] = a[p]; a[p] = t
            val pv = a[c][c].takeIf { abs(it) > 1e-12 } ?: 1e-12
            for (k in c until 9) a[c][k] /= pv
            for (rr in 0 until 8) if (rr != c) {
                val f = a[rr][c]
                if (f != 0.0) for (k in c until 9) a[rr][k] -= f * a[c][k]
            }
        }
        return DoubleArray(8) { a[it][8] }
    }

    // ---------------------------------------------------------------- filters

    fun apply(src: Img, f: Filter): Img = when (f) {
        Filter.ORIGINAL -> src
        Filter.AUTO -> auto(src)
        Filter.MAGIC -> magic(src)
        Filter.GRAY -> gray(src)
        Filter.BW -> bw(src)
        Filter.PHOTO -> photo(src)
    }

    /**
     * Estimates the paper (background) color per pixel: blocks take a high percentile
     * of their pixels (paper is the bright part), get smoothed, then are bilinearly upsampled.
     */
    private fun background(src: Img): Img {
        val bs = max(8, max(src.w, src.h) / 48)
        val gw = (src.w + bs - 1) / bs; val gh = (src.h + bs - 1) / bs
        val grid = Img(gw, gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            java.util.Arrays.fill(hist, 0)
            val x0 = gx * bs; val y0 = gy * bs
            val x1 = min(src.w, x0 + bs); val y1 = min(src.h, y0 + bs)
            var n = 0
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { hist[luma(src.px[y * src.w + x])]++; n++; x += 2 }
                y += 2
            }
            // 90th percentile luma = paper brightness in this block
            var acc = 0; var thr = 255
            for (v in 255 downTo 0) { acc += hist[v]; if (acc >= n / 10) { thr = v; break } }
            var rr = 0L; var gg = 0L; var bb = 0L; var m = 0
            y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val c = src.px[y * src.w + x]
                    if (luma(c) >= thr - 4) { rr += r(c); gg += g(c); bb += b(c); m++ }
                    x += 2
                }
                y += 2
            }
            grid.px[gy * gw + gx] = if (m > 0) rgb((rr / m).toInt(), (gg / m).toInt(), (bb / m).toInt()) else rgb(thr, thr, thr)
        }
        // dark blocks (photos, big black areas) should not count as paper: replace with neighbours
        val l = IntArray(gw * gh) { luma(grid.px[it]) }
        val med = l.sorted()[l.size / 2]
        for (i in grid.px.indices) if (l[i] < med * 0.55) grid.px[i] = 0
        repeat(3) { fillHoles(grid) }
        for (i in grid.px.indices) if (grid.px[i] == 0) grid.px[i] = rgb(med, med, med)
        val sm = blur(blur(grid, 1), 1)
        return resizeSmooth(sm, src.w, src.h)
    }

    private fun fillHoles(g: Img) {
        val out = g.px.copyOf()
        for (y in 0 until g.h) for (x in 0 until g.w) {
            if (g.px[y * g.w + x] != 0) continue
            var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until g.w && yy in 0 until g.h) {
                    val c = g.px[yy * g.w + xx]
                    if (c != 0) { rr += r(c); gg += g(c); bb += b(c); n++ }
                }
            }
            if (n > 0) out[y * g.w + x] = rgb(rr / n, gg / n, bb / n)
        }
        System.arraycopy(out, 0, g.px, 0, out.size)
    }

    private fun resizeSmooth(src: Img, nw: Int, nh: Int): Img {
        val o = Img(nw, nh)
        val sx = src.w.toFloat() / nw; val sy = src.h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, src.h - 1f)
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, src.w - 1f)
                o.px[y * nw + x] = sample(src, fx, fy)
            }
        }
        return o
    }

    /** Separable box blur with radius [rad]. */
    fun blur(src: Img, rad: Int): Img {
        if (rad <= 0) return src
        val w = src.w; val h = src.h
        val tmp = IntArray(w * h); val out = Img(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (k in -rad..rad) { val xx = x + k; if (xx in 0 until w) { val c = src.px[y * w + xx]; rr += r(c); gg += g(c); bb += b(c); n++ } }
            tmp[y * w + x] = rgb(rr / n, gg / n, bb / n)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (k in -rad..rad) { val yy = y + k; if (yy in 0 until h) { val c = tmp[yy * w + x]; rr += r(c); gg += g(c); bb += b(c); n++ } }
            out.px[y * w + x] = rgb(rr / n, gg / n, bb / n)
        }
        return out
    }

    /** Divides out the background: paper becomes white, shadows and uneven light disappear. */
    private fun flatten(src: Img, strength: Float = 1f): Img {
        val bg = background(src)
        val o = Img(src.w, src.h)
        for (i in src.px.indices) {
            val c = src.px[i]; val k = bg.px[i]
            fun ch(v: Int, bv: Int): Int {
                val n = v * 255f / max(bv, 24)
                return (v + (n - v) * strength).roundToInt()
            }
            o.px[i] = rgb(ch(r(c), r(k)), ch(g(c), g(k)), ch(b(c), b(k)))
        }
        return o
    }

    /** Lifts lights to white and pushes darks down around the paper level. */
    private fun curve(src: Img, black: Int, white: Int, gamma: Float = 1f): Img {
        val lut = IntArray(256) { v ->
            val t = ((v - black).toFloat() / (white - black)).coerceIn(0f, 1f)
            (Math.pow(t.toDouble(), gamma.toDouble()) * 255).roundToInt()
        }
        val o = Img(src.w, src.h)
        for (i in src.px.indices) { val c = src.px[i]; o.px[i] = rgb(lut[r(c)], lut[g(c)], lut[b(c)]) }
        return o
    }

    fun saturate(src: Img, k: Float): Img {
        val o = Img(src.w, src.h)
        for (i in src.px.indices) {
            val c = src.px[i]; val l = luma(c)
            o.px[i] = rgb((l + (r(c) - l) * k).roundToInt(), (l + (g(c) - l) * k).roundToInt(), (l + (b(c) - l) * k).roundToInt())
        }
        return o
    }

    fun sharpen(src: Img, amount: Float = 0.6f, rad: Int = 1): Img {
        val bl = blur(src, rad)
        val o = Img(src.w, src.h)
        for (i in src.px.indices) {
            val c = src.px[i]; val d = bl.px[i]
            o.px[i] = rgb(
                (r(c) + (r(c) - r(d)) * amount).roundToInt(),
                (g(c) + (g(c) - g(d)) * amount).roundToInt(),
                (b(c) + (b(c) - b(d)) * amount).roundToInt(),
            )
        }
        return o
    }

    fun auto(src: Img): Img = curve(flatten(src, 0.85f), 18, 242, 1.05f)

    fun magic(src: Img): Img = sharpen(saturate(curve(flatten(src), 40, 235, 1.15f), 1.25f), 0.5f)

    fun gray(src: Img): Img {
        val f = curve(flatten(src), 35, 238, 1.1f)
        val o = Img(src.w, src.h)
        for (i in f.px.indices) { val l = luma(f.px[i]); o.px[i] = rgb(l, l, l) }
        return sharpen(o, 0.5f)
    }

    fun bw(src: Img): Img {
        val f = flatten(src)
        val sm = blur(f, 1)
        val o = Img(src.w, src.h)
        for (i in sm.px.indices) { val l = luma(sm.px[i]); o.px[i] = if (l < 175) 0xFF000000.toInt() else -1 }
        return o
    }

    /** Old-photo restoration: gray-world white balance, percentile stretch, a bit of color and crispness. */
    fun photo(src: Img): Img {
        var rs = 0L; var gs = 0L; var bs = 0L
        for (c in src.px) { rs += r(c); gs += g(c); bs += b(c) }
        val n = src.px.size.toDouble()
        val avg = (rs + gs + bs) / (3 * n)
        // only half-way to gray world, so sunsets stay warm
        val kr = (1 + (avg / max(1.0, rs / n) - 1) * 0.5).toFloat()
        val kg = (1 + (avg / max(1.0, gs / n) - 1) * 0.5).toFloat()
        val kb = (1 + (avg / max(1.0, bs / n) - 1) * 0.5).toFloat()
        val wb = Img(src.w, src.h)
        val hist = IntArray(256)
        for (i in src.px.indices) {
            val c = src.px[i]
            val v = rgb((r(c) * kr).roundToInt(), (g(c) * kg).roundToInt(), (b(c) * kb).roundToInt())
            wb.px[i] = v; hist[luma(v)]++
        }
        // stretch only part of the way: faded prints get their range back without crushing shadows
        val lo = percentile(hist, src.px.size, 0.002) * 6 / 10; val hi = 255 - (255 - percentile(hist, src.px.size, 0.998)) * 6 / 10
        val st = if (hi - lo > 40) curve(wb, lo, hi) else wb
        return sharpen(saturate(st, 1.08f), 0.35f)
    }

    private fun percentile(hist: IntArray, n: Int, p: Double): Int {
        var acc = 0; val target = (n * p).toInt()
        for (v in 0..255) { acc += hist[v]; if (acc > target) return v }
        return 255
    }

    // ---------------------------------------------------------------- photo glare removal

    /**
     * Merges several shots of the same print (each already cropped and deskewed by the scanner).
     * Glare is always *brighter* than the real print, so per pixel we average only the frames
     * that are close to the darkest one; bright outliers (reflections) drop out.
     */
    fun mergeGlare(frames: List<Img>): Img {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        val ws = frames.map { it.w }.sorted(); val hs = frames.map { it.h }.sorted()
        val tw = ws[ws.size / 2]; val th = hs[hs.size / 2]
        val sized = frames.map { resize(it, tw, th) }
        val ref = sized[0]
        val aligned = listOf(ref) + sized.drop(1).map { shift(it, align(ref, it)) }
        val o = Img(tw, th)
        val k = aligned.size
        // glare is judged on lightly blurred luma, so sensor noise doesn't decide the weights
        val lum = aligned.map { f -> val bl = blur(f, 2); IntArray(tw * th) { luma(bl.px[it]) } }
        val ls = IntArray(k)
        for (i in 0 until tw * th) {
            var lmin = 255
            for (f in 0 until k) { val l = lum[f][i]; ls[f] = l; if (l < lmin) lmin = l }
            var wsum = 0f; var rr = 0f; var gg = 0f; var bb = 0f
            for (f in 0 until k) {
                val over = (ls[f] - lmin - 3).coerceAtLeast(0)
                val wt = exp(-over / 2.5f)
                val c = aligned[f].px[i]
                rr += r(c) * wt; gg += g(c) * wt; bb += b(c) * wt; wsum += wt
            }
            o.px[i] = rgb((rr / wsum).roundToInt(), (gg / wsum).roundToInt(), (bb / wsum).roundToInt())
        }
        return o
    }

    /** Best integer translation (dx, dy) of [img] onto [ref], searched on downscaled luma. */
    fun align(ref: Img, img: Img): Pair<Int, Int> {
        val s = 320f / max(ref.w, ref.h)
        val sw = max(8, (ref.w * s).roundToInt()); val sh = max(8, (ref.h * s).roundToInt())
        val a = resize(ref, sw, sh); val bimg = resize(img, sw, sh)
        val la = IntArray(sw * sh) { luma(a.px[it]) }; val lb = IntArray(sw * sh) { luma(bimg.px[it]) }
        val rng = 10
        var best = Long.MAX_VALUE; var bx = 0; var by = 0
        for (dy in -rng..rng) for (dx in -rng..rng) {
            var sum = 0L; var n = 0
            var y = rng
            while (y < sh - rng) {
                var x = rng
                while (x < sw - rng) {
                    val d = la[y * sw + x] - lb[(y - dy) * sw + (x - dx)]
                    sum += min(abs(d), 60); n++
                    x += 2
                }
                y += 2
            }
            if (n > 0 && sum < best) { best = sum; bx = dx; by = dy }
        }
        return (bx / s).roundToInt() to (by / s).roundToInt()
    }

    private fun shift(src: Img, d: Pair<Int, Int>): Img {
        val (dx, dy) = d
        if (dx == 0 && dy == 0) return src
        val o = Img(src.w, src.h)
        for (y in 0 until src.h) {
            val sy = (y - dy).coerceIn(0, src.h - 1)
            for (x in 0 until src.w) o.px[y * src.w + x] = src.px[sy * src.w + (x - dx).coerceIn(0, src.w - 1)]
        }
        return o
    }

    // ---------------------------------------------------------------- ID cards

    enum class IdKind(val label: String, val wMm: Float, val hMm: Float, val sides: Int) {
        ID_CARD("Lična karta", 85.6f, 54f, 2),
        DRIVER("Vozačka dozvola", 85.6f, 54f, 2),
        PASSPORT("Pasoš", 125f, 88f, 1),
    }

    /** Places card sides at true size on a white A4 page at [dpi]. */
    fun composeId(sides: List<Img>, kind: IdKind, dpi: Int = 300): Img {
        val mm = dpi / 25.4f
        val pw = (210 * mm).roundToInt(); val ph = (297 * mm).roundToInt()
        val page = Img(pw, ph).also { java.util.Arrays.fill(it.px, -1) }
        val cw = (kind.wMm * mm).roundToInt(); val ch = (kind.hMm * mm).roundToInt()
        val gap = (20 * mm).roundToInt()
        val n = min(sides.size, kind.sides).coerceAtLeast(1)
        val total = n * ch + (n - 1) * gap
        var top = (25 * mm).roundToInt().coerceAtMost((ph - total) / 2)
        for (i in 0 until n) {
            var s = sides[i]
            // the card is landscape; if the shot came out portrait, turn it
            if (s.h > s.w) s = rotate(s, 90)
            val fitted = resize(s, cw, ch)
            val left = (pw - cw) / 2
            for (y in 0 until ch) System.arraycopy(fitted.px, y * cw, page.px, (top + y) * pw + left, cw)
            top += ch + gap
        }
        return page
    }

    /** Alpha-blends [stamp] (with transparency) onto [base] with its top-left at (x, y) scaled to width [w]. */
    fun stamp(base: Img, stamp: Img, x: Int, y: Int, w: Int): Img {
        if (w <= 0) return base
        val h = (stamp.h * w.toFloat() / stamp.w).roundToInt().coerceAtLeast(1)
        val s = resizeAlpha(stamp, w, h)
        val o = base.copy()
        for (yy in 0 until h) {
            val ty = y + yy; if (ty !in 0 until o.h) continue
            for (xx in 0 until w) {
                val tx = x + xx; if (tx !in 0 until o.w) continue
                val c = s.px[yy * w + xx]; val a = (c ushr 24) and 0xFF
                if (a == 0) continue
                val d = o.px[ty * o.w + tx]
                o.px[ty * o.w + tx] = rgb(
                    (r(c) * a + r(d) * (255 - a)) / 255, (g(c) * a + g(d) * (255 - a)) / 255, (b(c) * a + b(d) * (255 - a)) / 255,
                )
            }
        }
        return o
    }

    private fun resizeAlpha(src: Img, nw: Int, nh: Int): Img {
        val o = Img(nw, nh)
        val sx = src.w.toFloat() / nw; val sy = src.h.toFloat() / nh
        for (y in 0 until nh) for (x in 0 until nw) {
            // area sample: take the most opaque source pixel in the footprint (keeps thin pen strokes)
            val x0 = (x * sx).toInt(); val y0 = (y * sy).toInt()
            val x1 = min(src.w, max(x0 + 1, ((x + 1) * sx).toInt())); val y1 = min(src.h, max(y0 + 1, ((y + 1) * sy).toInt()))
            var best = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val c = src.px[yy * src.w + xx]
                if ((c ushr 24) > (best ushr 24)) best = c
            }
            o.px[y * nw + x] = best
        }
        return o
    }
}
