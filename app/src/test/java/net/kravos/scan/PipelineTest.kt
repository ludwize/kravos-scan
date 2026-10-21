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
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Runs the image pipeline on synthetic camera shots and writes the results to
 * build/test-out, which CI publishes so the outputs can be looked at.
 */
class PipelineTest {
    private val out = File(System.getProperty("user.dir"), "build/test-out").apply { mkdirs() }

    private fun toImg(b: BufferedImage) = Img(b.width, b.height, b.getRGB(0, 0, b.width, b.height, null, 0, b.width))
    private fun save(i: Img, name: String) {
        val b = BufferedImage(i.w, i.h, BufferedImage.TYPE_INT_RGB); b.setRGB(0, 0, i.w, i.h, i.px, 0, i.w)
        ImageIO.write(b, "png", File(out, "$name.png"))
    }
    private fun jpeg(i: Img): ByteArray {
        val b = BufferedImage(i.w, i.h, BufferedImage.TYPE_INT_RGB); b.setRGB(0, 0, i.w, i.h, i.px, 0, i.w)
        return ByteArrayOutputStream().also { ImageIO.write(b, "jpg", it) }.toByteArray()
    }

    /** Skewed, shadowed page photographed on a dark table. */
    private fun photoOfPage(): Pair<Img, FloatArray> {
        val w = 1400; val h = 1800
        val b = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = b.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(60, 45, 35); g.fillRect(0, 0, w, h)
        val quad = intArrayOf(180, 140, 1250, 210, 1300, 1650, 120, 1580)
        g.paint = GradientPaint(0f, 0f, Color(238, 232, 218), w.toFloat(), h.toFloat(), Color(140, 130, 112))
        g.fillPolygon(intArrayOf(quad[0], quad[2], quad[4], quad[6]), intArrayOf(quad[1], quad[3], quad[5], quad[7]), 4)
        g.color = Color(35, 35, 45); g.font = Font(Font.SERIF, Font.PLAIN, 38)
        val lines = listOf("УГОВОР О ЗАКУПУ", "Zakupac plaća do petog u mesecu.", "Beograd, 21. oktobar 2026.", "The quick brown fox.")
        lines.forEachIndexed { i, s -> g.drawString(s, 260 + i * 4, 330 + i * 90) }
        for (r in 0..6) g.drawLine(260, 800 + r * 70, 1120, 805 + r * 70)
        g.dispose()
        return toImg(b) to FloatArray(8) { quad[it].toFloat() }
    }

    @Test fun documentFilters() {
        val (shot, quad) = photoOfPage()
        save(shot, "00-camera")
        val flat = Ops.warp(shot, quad)
        save(flat, "01-warped")
        for (f in Filter.entries) save(Ops.apply(flat, f), "02-filter-${f.name.lowercase()}")
        val rot = Ops.rotate(flat, 90)
        assertTrue(rot.w == flat.h && rot.h == flat.w)
    }

    @Test fun glareMerge() {
        fun shot(gx: Int, gy: Int, dx: Int): Img {
            val b = BufferedImage(1200, 840, BufferedImage.TYPE_INT_RGB)
            val g = b.createGraphics()
            g.translate(dx, 0)
            g.paint = GradientPaint(0f, 0f, Color(150, 175, 185), 0f, 460f, Color(205, 200, 170)); g.fillRect(-20, 0, 1240, 460)
            g.paint = GradientPaint(0f, 460f, Color(140, 150, 90), 0f, 840f, Color(110, 105, 70)); g.fillRect(-20, 460, 1240, 380)
            g.color = Color(70, 85, 55); for (i in 0 until 6) g.fillOval(100 + i * 180, 360, 140, 140)
            g.color = Color(170, 120, 95); g.fillRect(400, 340, 220, 180)
            g.translate(-dx, 0)
            g.paint = RadialGradientPaint(gx.toFloat(), gy.toFloat(), 220f, floatArrayOf(0f, 0.45f, 1f),
                arrayOf(Color(255, 255, 255, 250), Color(255, 255, 255, 170), Color(255, 255, 255, 0)))
            g.fillOval(gx - 220, gy - 220, 440, 440)
            g.dispose()
            return toImg(b)
        }
        val shots = listOf(shot(300, 250, 0), shot(850, 550, 5), shot(560, 680, -4))
        shots.forEachIndexed { i, s -> save(s, "10-glare-shot$i") }
        val m = Ops.mergeGlare(shots)
        save(m, "11-glare-merged")
        save(Ops.photo(m), "12-glare-merged-restored")
        // glare center of shot 0 should be much darker after merging
        val before = shots[0].px[250 * 1200 + 300] and 0xFF
        val after = m.px[250 * 1200 + 300] and 0xFF
        assertTrue("glare not removed: $before -> $after", after < before - 40)
    }

    @Test fun idCardAndPdf() {
        fun card(t: String): Img {
            val b = BufferedImage(1000, 640, BufferedImage.TYPE_INT_RGB)
            val g = b.createGraphics(); g.color = Color(205, 218, 235); g.fillRect(0, 0, 1000, 640)
            g.color = Color(30, 40, 70); g.font = Font(Font.SANS_SERIF, Font.BOLD, 44); g.drawString(t, 50, 90); g.dispose()
            return toImg(b)
        }
        val page = Ops.composeId(listOf(card("ЛИЧНА КАРТА"), card("POLEĐINA")), Ops.IdKind.ID_CARD, 150)
        save(page, "20-id-a4")
        val words = listOf(Word("Уговор", 100, 100, 400, 160), Word("Zakupac", 100, 200, 420, 250))
        val hocr = "<span class='ocrx_word' id='word_1_1' title='bbox 36 92 96 116; x_wconf 93'>Z&amp;K</span>"
        assertTrue(Hocr.parse(hocr).single() == Word("Z&K", 36, 92, 96, 116))
        val pdf = File(out, "21-test.pdf")
        pdf.outputStream().use {
            PdfWriter(PageSize.A4, null, "Тест").write(listOf(PdfPage(jpeg(page), page.w, page.h, words = words), PdfPage(jpeg(card("x")), 1000, 640)), it)
        }
        val s = pdf.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.5") && s.contains("/Count 2") && s.trimEnd().endsWith("%%EOF"))
    }
}
