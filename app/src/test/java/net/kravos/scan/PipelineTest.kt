package net.kravos.scan

import net.kravos.scan.img.Filter
import net.kravos.scan.img.Img
import net.kravos.scan.img.Ops
import net.kravos.scan.pdf.Hocr
import net.kravos.scan.pdf.PageSize
import net.kravos.scan.pdf.PdfPage
import net.kravos.scan.pdf.PdfWriter
import net.kravos.scan.pdf.Word
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Runs the image pipeline on synthetic camera shots (drawn in plain Kotlin — Android unit
 * tests have no AWT) and writes PNGs to build/test-out, which CI publishes for inspection.
 */
class PipelineTest {
    private val out = File(System.getProperty("user.dir"), "build/test-out").apply { mkdirs() }

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun png(i: Img, name: String) {
        val raw = ByteArrayOutputStream()
        for (y in 0 until i.h) {
            raw.write(0)
            for (x in 0 until i.w) { val c = i.px[y * i.w + x]; raw.write((c shr 16) and 255); raw.write((c shr 8) and 255); raw.write(c and 255) }
        }
        val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            d.writeInt(data.size); val t = type.toByteArray(); d.write(t); d.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data); d.writeInt(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(i.w); writeInt(i.h); write(byteArrayOf(8, 2, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr); chunk("IDAT", PdfWriter.deflate(raw.toByteArray())); chunk("IEND", ByteArray(0))
        File(out, "$name.png").writeBytes(bo.toByteArray())
    }

    private fun inside(px: Float, py: Float, q: FloatArray): Boolean {
        var c = false; var j = 3
        for (i in 0 until 4) {
            val xi = q[2 * i]; val yi = q[2 * i + 1]; val xj = q[2 * j]; val yj = q[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Skewed page with "text" bars, shadowed toward the bottom-right, on a dark table. */
    private fun photoOfPage(): Pair<Img, FloatArray> {
        val w = 1400; val h = 1800
        val q = floatArrayOf(180f, 140f, 1250f, 210f, 1300f, 1650f, 120f, 1580f)
        val img = Img(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            img.px[y * w + x] = if (inside(x.toFloat(), y.toFloat(), q)) {
                val t = (x + y).toFloat() / (w + h)
                val base = rgb((238 - 100 * t).toInt(), (232 - 102 * t).toInt(), (218 - 106 * t).toInt())
                val line = (y - 300) / 60; val ly = (y - 300) % 60
                val isText = y in 300..1300 && ly in 14..34 && x in 280..(1100 - (line % 4) * 140) && ((x / 23) % 5 != 0)
                if (isText) rgb(35, 35, 45) else base
            } else rgb(60, 45, 35)
        }
        return img to q
    }

    @Test fun documentFilters() {
        val (shot, quad) = photoOfPage()
        png(shot, "00-camera")
        val flat = Ops.warp(shot, quad)
        png(flat, "01-warped")
        for (f in Filter.entries) png(Ops.apply(flat, f), "02-filter-${f.name.lowercase()}")
        val rot = Ops.rotate(flat, 90)
        assertTrue(rot.w == flat.h && rot.h == flat.w)
        // magic filter must flatten the shadow: paper at both ends of the gradient ends up near white
        val m = Ops.magic(flat)
        val tl = m.px[40 * m.w + 40] and 0xFF; val br = m.px[(m.h - 40) * m.w + (m.w - 40)] and 0xFF
        assertTrue("paper not flattened: $tl / $br", tl > 220 && br > 220)
    }

    private fun print(gx: Int, gy: Int, dx: Int): Img {
        val w = 1200; val h = 840
        val img = Img(w, h)
        for (y in 0 until h) for (xx in 0 until w) {
            val x = xx - dx
            var c = if (y < 460) { val t = y / 460f; rgb((150 + 55 * t).toInt(), (175 + 25 * t).toInt(), (185 - 15 * t).toInt()) }
            else { val t = (y - 460) / 380f; rgb((140 - 30 * t).toInt(), (150 - 45 * t).toInt(), (90 - 20 * t).toInt()) }
            for (i in 0 until 6) if (hypot((x - (170 + i * 180)).toFloat(), (y - 430).toFloat()) < 70) c = rgb(70, 85, 55)
            if (x in 400..620 && y in 340..520) c = rgb(170, 120, 95)
            // glare: additive white, radial falloff
            val d = hypot((xx - gx).toFloat(), (y - gy).toFloat()) / 220f
            if (d < 1f) {
                val a = (1 - d) * 0.98f
                c = rgb((((c shr 16) and 255) + (255 - ((c shr 16) and 255)) * a).toInt(), (((c shr 8) and 255) + (255 - ((c shr 8) and 255)) * a).toInt(), ((c and 255) + (255 - (c and 255)) * a).toInt())
            }
            img.px[y * w + xx] = c
        }
        return img
    }

    @Test fun glareMerge() {
        val shots = listOf(print(300, 250, 0), print(850, 550, 5), print(560, 680, -4))
        shots.forEachIndexed { i, s -> png(s, "10-glare-shot$i") }
        val m = Ops.mergeGlare(shots)
        png(m, "11-glare-merged")
        png(Ops.photo(m), "12-glare-merged-restored")
        val before = shots[0].px[250 * 1200 + 300] and 0xFF
        val after = m.px[250 * 1200 + 300] and 0xFF
        assertTrue("glare not removed: $before -> $after", after < before - 40)
    }

    @Test fun idCardAndPdf() {
        fun card(shade: Int): Img = Img(1000, 640).also { c -> for (i in c.px.indices) c.px[i] = rgb(205 - shade, 218 - shade, 235 - shade) }
        val page = Ops.composeId(listOf(card(0), card(40)), Ops.IdKind.ID_CARD, 150)
        png(page, "20-id-a4")
        // card at true size: 85.6 mm @150 dpi ≈ 506 px
        val row = page.h / 4
        var first = -1; var last = -1
        for (x in 0 until page.w) if (page.px[(page.h * 0) + row * page.w + x] != -1 && page.px[row * page.w + x] != rgb(255, 255, 255)) { if (first < 0) first = x; last = x }
        assertTrue("card width ${last - first}", first < 0 || (last - first) in 480..530)
        val hocr = "<span class='ocrx_word' id='word_1_1' title='bbox 36 92 96 116; x_wconf 93'>Z&amp;K</span>"
        assertTrue(Hocr.parse(hocr).single() == Word("Z&K", 36, 92, 96, 116))
        val pdf = File(out, "21-test.pdf")
        val fakeJpeg = byteArrayOf(-1, -40, -1, -39)
        pdf.outputStream().use {
            PdfWriter(PageSize.A4, null, "Тест").write(
                listOf(PdfPage(fakeJpeg, page.w, page.h, words = listOf(Word("Уговор", 100, 100, 400, 160))), PdfPage(fakeJpeg, 1000, 640)), it,
            )
        }
        val s = pdf.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.5") && s.contains("/Count 2") && s.contains("/MediaBox [0 0 841.89 595.28]") && s.trimEnd().endsWith("%%EOF"))
        @Suppress("UNUSED_VARIABLE") val unused = max(0, min(1, 2))
    }
}
