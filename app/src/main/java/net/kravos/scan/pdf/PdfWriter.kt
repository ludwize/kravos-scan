package net.kravos.scan.pdf

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

/** One OCR word with its box in image pixels. */
data class Word(val text: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

enum class PageSize(val label: String, val wPt: Float, val hPt: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    AUTO("Kao slika", 0f, 0f),
}

class PdfPage(
    val jpeg: ByteArray,
    val imgW: Int,
    val imgH: Int,
    /** Resolution used for [PageSize.AUTO] (page = image at this dpi). */
    val dpi: Int = 300,
    val words: List<Word> = emptyList(),
)

/**
 * Minimal PDF writer: embeds JPEGs as-is (no recompression, so scan quality is kept)
 * and, when OCR words are given, adds an invisible text layer so the PDF is searchable
 * and text can be copied — the same technique Tesseract uses (glyph-less CID font,
 * code points written directly as CIDs, identity ToUnicode map).
 */
class PdfWriter(private val size: PageSize, private val glyphlessFont: ByteArray?, private val title: String) {

    private class Obj(val id: Int, var body: ByteArray)

    private val objs = mutableListOf<Obj>()
    private fun alloc(): Int { objs += Obj(objs.size + 1, ByteArray(0)); return objs.size }
    private fun set(id: Int, s: String) { objs[id - 1].body = s.toByteArray(Charsets.ISO_8859_1) }
    private fun setStream(id: Int, dict: String, data: ByteArray) {
        val head = "<< $dict /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1)
        val tail = "\nendstream".toByteArray(Charsets.ISO_8859_1)
        objs[id - 1].body = head + data + tail
    }

    fun write(pages: List<PdfPage>, out: OutputStream) {
        objs.clear()
        val catalog = alloc(); val pagesId = alloc(); val info = alloc()
        val font = if (pages.any { it.words.isNotEmpty() }) buildFont() else 0
        val kids = mutableListOf<Int>()
        for ((i, p) in pages.withIndex()) {
            val pageId = alloc(); val imgId = alloc(); val contentId = alloc()
            kids += pageId
            val (pw, ph) = when (size) {
                PageSize.AUTO -> p.imgW * 72f / p.dpi to p.imgH * 72f / p.dpi
                // landscape scans get a landscape page
                else -> if (p.imgW > p.imgH) size.hPt to size.wPt else size.wPt to size.hPt
            }
            val s = min(pw / p.imgW, ph / p.imgH)
            val dw = p.imgW * s; val dh = p.imgH * s
            val ox = (pw - dw) / 2; val oy = (ph - dh) / 2
            setStream(imgId, "/Type /XObject /Subtype /Image /Width ${p.imgW} /Height ${p.imgH} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", p.jpeg)
            val cs = StringBuilder()
            cs.append("q ${f(dw)} 0 0 ${f(dh)} ${f(ox)} ${f(oy)} cm /Im$i Do Q\n")
            if (font != 0 && p.words.isNotEmpty()) {
                cs.append("BT 3 Tr\n")
                for (wd in p.words) {
                    val cps = wd.text.codePoints().toArray().filter { it in 1..0xFFFF }
                    if (cps.isEmpty()) continue
                    val bw = (wd.x1 - wd.x0) * s; val bh = (wd.y1 - wd.y0) * s
                    if (bw <= 0.5f || bh <= 0.5f) continue
                    val fs = bh
                    val tz = 100f * bw / (cps.size * 0.5f * fs)
                    val x = ox + wd.x0 * s; val y = oy + (p.imgH - wd.y1) * s
                    val hex = cps.joinToString("") { "%04X".format(it) }
                    cs.append("/F1 ${f(fs)} Tf ${f(tz)} Tz 1 0 0 1 ${f(x)} ${f(y)} Tm <$hex> Tj\n")
                }
                cs.append("ET\n")
            }
            setStream(contentId, "/Filter /FlateDecode", deflate(cs.toString().toByteArray(Charsets.ISO_8859_1)))
            val fontRes = if (font != 0) " /Font << /F1 $font 0 R >>" else ""
            set(pageId, "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] /Resources << /XObject << /Im$i $imgId 0 R >>$fontRes >> /Contents $contentId 0 R >>")
        }
        set(pagesId, "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>")
        set(catalog, "<< /Type /Catalog /Pages $pagesId 0 R >>")
        set(info, "<< /Producer (Kravos Sken) /Title ${pdfString(title)} >>")

        val buf = ByteArrayOutputStream()
        buf.write("%PDF-1.5\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offs = IntArray(objs.size)
        for (o in objs) {
            offs[o.id - 1] = buf.size()
            buf.write("${o.id} 0 obj\n".toByteArray(Charsets.ISO_8859_1)); buf.write(o.body); buf.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = buf.size()
        val sb = StringBuilder("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (o in offs) sb.append("%010d 00000 n \n".format(o))
        sb.append("trailer\n<< /Size ${objs.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        buf.writeTo(out)
    }

    private fun buildFont(): Int {
        val type0 = alloc(); val cid = alloc(); val map = alloc(); val toU = alloc(); val desc = alloc()
        val file = if (glyphlessFont != null) alloc() else 0
        set(type0, "<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H /DescendantFonts [$cid 0 R] /ToUnicode $toU 0 R >>")
        set(cid, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont /CIDToGIDMap $map 0 R /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor $desc 0 R /DW 500 >>")
        // every CID → glyph 1 (the single empty glyph of the font)
        val m = ByteArray(65536 * 2); for (i in 0 until 65536) m[2 * i + 1] = 1
        setStream(map, "/Filter /FlateDecode", deflate(m))
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /Adobe-Identify-UCS def
            /CMapType 2 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            1 beginbfrange
            <0000> <FFFF> <0000>
            endbfrange
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
        """.trimIndent()
        setStream(toU, "", cmap.toByteArray(Charsets.ISO_8859_1))
        val ff = if (file != 0) " /FontFile2 $file 0 R" else ""
        set(desc, "<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000] /ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80$ff >>")
        if (file != 0) setStream(file, "/Length1 ${glyphlessFont!!.size} /Filter /FlateDecode", deflate(glyphlessFont))
        return type0
    }

    private fun f(v: Float): String {
        val s = "%.3f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    private fun pdfString(s: String): String {
        // UTF-16BE with BOM, hex encoded: safe for any title (Cyrillic etc.)
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) } + ">"
    }

    companion object {
        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(data); d.finish()
            val out = ByteArrayOutputStream(max(64, data.size / 4)); val buf = ByteArray(16384)
            while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
            d.end(); return out.toByteArray()
        }
    }
}

/** Parses Tesseract hOCR output into words with pixel boxes. */
object Hocr {
    private val wordRe = Regex("""<span class=['"]ocrx_word['"][^>]*?title=['"]bbox (\d+) (\d+) (\d+) (\d+)[^'"]*['"][^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
    private val tagRe = Regex("<[^>]+>")

    fun parse(hocr: String): List<Word> = wordRe.findAll(hocr).mapNotNull { m ->
        val (x0, y0, x1, y1, inner) = m.destructured
        val t = unescape(tagRe.replace(inner, "")).trim()
        if (t.isEmpty()) null else Word(t, x0.toInt(), y0.toInt(), x1.toInt(), y1.toInt())
    }.toList()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
