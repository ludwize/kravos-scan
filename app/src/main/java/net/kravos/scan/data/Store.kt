package net.kravos.scan.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kravos.scan.img.Bitmaps
import net.kravos.scan.img.Filter
import net.kravos.scan.img.Img
import net.kravos.scan.img.Ops
import net.kravos.scan.pdf.Word
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class DocKind(val label: String) { DOC("Dokument"), PHOTO("Fotografija"), ID("Lična karta") }

/** Signature placed on a page. Position/size are fractions of the rendered page. */
data class Stamp(val x: Float, val y: Float, val w: Float)

data class Page(
    val id: String,
    /** Original captured image (already cropped by the scanner when it came from the camera). */
    val orig: String,
    val filter: Filter,
    val rot: Int = 0,
    /** Manual crop quad TL,TR,BR,BL as fractions of the original image, or null. */
    val quad: List<Float>? = null,
    val stamps: List<Stamp> = emptyList(),
    /** Rendered (cropped/rotated/filtered/stamped) JPEG, empty when it must be re-rendered. */
    val rendered: String = "",
    val renderW: Int = 0,
    val renderH: Int = 0,
    /** OCR result of the current render; null = not run yet. */
    val text: String? = null,
)

data class Doc(
    val id: String,
    val name: String,
    val kind: DocKind,
    val created: Long,
    val updated: Long,
    val pages: List<Page>,
) {
    val thumb: String? get() = pages.firstOrNull()?.rendered?.takeIf { it.isNotEmpty() }
    val fullText: String get() = pages.mapNotNull { it.text }.joinToString("\n\n")
    val hasOcr: Boolean get() = pages.isNotEmpty() && pages.all { it.text != null }
}

/**
 * Documents live in filesDir/docs/<id>/ — original images, rendered pages and doc.json.
 * Everything is local; nothing leaves the phone unless the user exports it.
 */
class Store(private val ctx: Context, private val prefs: Prefs) {
    private val root = File(ctx.filesDir, "docs").apply { mkdirs() }
    private val lock = Mutex()
    private val _docs = MutableStateFlow(loadAll())
    val docs: StateFlow<List<Doc>> = _docs

    fun dir(id: String) = File(root, id)
    fun file(doc: Doc, name: String) = File(dir(doc.id), name)
    fun file(docId: String, name: String) = File(dir(docId), name)
    fun get(id: String): Doc? = _docs.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------ create / import

    private fun newName(kind: DocKind): String {
        val ts = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(Date())
        return "${kind.label} $ts"
    }

    /** Creates a document from image files/uris (scanner output, gallery, share). */
    suspend fun create(kind: DocKind, sources: List<Uri>, filter: Filter = prefs.defaultFilter(kind), name: String? = null): Doc =
        withContext(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().take(13)
            val d = dir(id).apply { mkdirs() }
            val pages = sources.map { uri ->
                val pid = UUID.randomUUID().toString().take(8)
                val f = File(d, "o_$pid.jpg")
                Bitmaps.copyUri(ctx, uri, f)
                normalizeOriginal(f)
                Page(pid, f.name, filter)
            }
            val now = System.currentTimeMillis()
            val doc = Doc(id, name ?: newName(kind), kind, now, now, pages)
            save(doc)
            renderAll(doc.id)
            get(doc.id)!!
        }

    /** Creates a document from an already processed image (photo merge, ID composition). */
    suspend fun createFromImg(kind: DocKind, img: Img, filter: Filter, name: String? = null): Doc = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(13)
        val d = dir(id).apply { mkdirs() }
        val pid = UUID.randomUUID().toString().take(8)
        val f = File(d, "o_$pid.jpg")
        Bitmaps.saveJpeg(img, f, 97)
        val now = System.currentTimeMillis()
        val doc = Doc(id, name ?: newName(kind), kind, now, now, listOf(Page(pid, f.name, filter)))
        save(doc)
        renderAll(doc.id)
        get(doc.id)!!
    }

    suspend fun addPages(docId: String, sources: List<Uri>) = withContext(Dispatchers.IO) {
        val doc = get(docId) ?: return@withContext
        val filter = doc.pages.lastOrNull()?.filter ?: prefs.defaultFilter(doc.kind)
        val pages = sources.map { uri ->
            val pid = UUID.randomUUID().toString().take(8)
            val f = file(docId, "o_$pid.jpg")
            Bitmaps.copyUri(ctx, uri, f)
            normalizeOriginal(f)
            Page(pid, f.name, filter)
        }
        update(docId) { it.copy(pages = it.pages + pages) }
        renderAll(docId)
    }

    suspend fun addImgPage(docId: String, img: Img) = withContext(Dispatchers.IO) {
        val doc = get(docId) ?: return@withContext
        val pid = UUID.randomUUID().toString().take(8)
        val f = file(docId, "o_$pid.jpg")
        Bitmaps.saveJpeg(img, f, 97)
        update(docId) { it.copy(pages = it.pages + Page(pid, f.name, doc.pages.lastOrNull()?.filter ?: prefs.defaultFilter(doc.kind))) }
        renderAll(docId)
    }

    /** Bakes EXIF rotation in and caps resolution, so every later step can read the file plainly. */
    private fun normalizeOriginal(f: File) {
        val (w, h) = Bitmaps.boundsOf(f)
        val rot = runCatching { androidx.exifinterface.media.ExifInterface(f.path).rotationDegrees }.getOrDefault(0)
        if (rot == 0 && maxOf(w, h) <= Bitmaps.MAX_SIDE) return
        val img = Bitmaps.decodeImg(f)
        Bitmaps.saveJpeg(img, f, 97)
    }

    // ------------------------------------------------------------ edits

    suspend fun rename(docId: String, name: String) = update(docId) { it.copy(name = name.trim().ifEmpty { it.name }) }

    suspend fun delete(docId: String) = lock.withLock {
        withContext(Dispatchers.IO) { dir(docId).deleteRecursively() }
        _docs.value = _docs.value.filterNot { it.id == docId }
    }

    suspend fun deletePage(docId: String, pageId: String) {
        val doc = get(docId) ?: return
        if (doc.pages.size <= 1) { delete(docId); return }
        val p = doc.pages.firstOrNull { it.id == pageId } ?: return
        update(docId) { it.copy(pages = it.pages.filterNot { x -> x.id == pageId }) }
        withContext(Dispatchers.IO) { file(docId, p.orig).delete(); if (p.rendered.isNotEmpty()) file(docId, p.rendered).delete() }
    }

    suspend fun movePage(docId: String, pageId: String, delta: Int) = update(docId) { d ->
        val i = d.pages.indexOfFirst { it.id == pageId }
        val j = (i + delta).coerceIn(0, d.pages.lastIndex)
        if (i < 0 || i == j) d else d.copy(pages = d.pages.toMutableList().apply { add(j, removeAt(i)) })
    }

    suspend fun setFilter(docId: String, pageId: String?, f: Filter) {
        update(docId) { d -> d.copy(pages = d.pages.map { if (pageId == null || it.id == pageId) it.copy(filter = f, rendered = "", text = null) else it }) }
        renderAll(docId)
    }

    suspend fun rotate(docId: String, pageId: String, deg: Int) {
        update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(rot = (it.rot + deg + 360) % 360, rendered = "", text = null, stamps = emptyList()) else it }) }
        renderAll(docId)
    }

    suspend fun setQuad(docId: String, pageId: String, quad: List<Float>?) {
        update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(quad = quad, rendered = "", text = null, stamps = emptyList()) else it }) }
        renderAll(docId)
    }

    suspend fun setStamps(docId: String, pageId: String, stamps: List<Stamp>) {
        update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(stamps = stamps, rendered = "", text = null) else it }) }
        renderAll(docId)
    }

    suspend fun setOcr(docId: String, pageId: String, text: String, words: List<Word>) {
        withContext(Dispatchers.IO) {
            val a = JSONArray()
            words.forEach { a.put(JSONArray().put(it.text).put(it.x0).put(it.y0).put(it.x1).put(it.y1)) }
            file(docId, "w_$pageId.json").writeText(a.toString())
        }
        update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) it.copy(text = text) else it }) }
    }

    fun words(docId: String, page: Page): List<Word> = runCatching {
        if (page.text == null) return emptyList()
        val a = JSONArray(file(docId, "w_${page.id}.json").readText())
        (0 until a.length()).map { val w = a.getJSONArray(it); Word(w.getString(0), w.getInt(1), w.getInt(2), w.getInt(3), w.getInt(4)) }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------ rendering

    /** Renders every page that needs it (crop → rotate → filter → signature). */
    suspend fun renderAll(docId: String) = withContext(Dispatchers.Default) {
        val doc = get(docId) ?: return@withContext
        for (p in doc.pages) if (p.rendered.isEmpty() || !file(docId, p.rendered).exists()) render(docId, p)
    }

    private suspend fun render(docId: String, p: Page) {
        val src = file(docId, p.orig)
        if (!src.exists()) return
        var img = Bitmaps.decodeImg(src)
        p.quad?.takeIf { it.size == 8 }?.let { q ->
            img = Ops.warp(img, FloatArray(8) { i -> q[i] * if (i % 2 == 0) img.w else img.h })
        }
        img = Ops.rotate(img, p.rot)
        img = Ops.apply(img, p.filter)
        val sig = signatureFile()
        if (p.stamps.isNotEmpty() && sig.exists()) {
            val bm = android.graphics.BitmapFactory.decodeFile(sig.path)
            if (bm != null) {
                val s = Bitmaps.toImg(bm); bm.recycle()
                for (st in p.stamps) img = Ops.stamp(img, s, (st.x * img.w).toInt(), (st.y * img.h).toInt(), (st.w * img.w).toInt())
            }
        }
        val name = "r_${p.id}_${System.currentTimeMillis() % 1_000_000}.jpg"
        withContext(Dispatchers.IO) { Bitmaps.saveJpeg(img, file(docId, name), prefs.jpegQuality) }
        val old = p.rendered
        update(docId) { d -> d.copy(pages = d.pages.map { if (it.id == p.id) it.copy(rendered = name, renderW = img.w, renderH = img.h) else it }) }
        if (old.isNotEmpty() && old != name) file(docId, old).delete()
    }

    fun signatureFile() = File(ctx.filesDir, "signature.png")

    // ------------------------------------------------------------ persistence

    private suspend fun update(docId: String, fn: (Doc) -> Doc) = lock.withLock {
        val cur = _docs.value.firstOrNull { it.id == docId } ?: return@withLock
        val next = fn(cur).copy(updated = System.currentTimeMillis())
        withContext(Dispatchers.IO) { write(next) }
        _docs.value = (_docs.value.filterNot { it.id == docId } + next).sortedByDescending { it.updated }
    }

    private suspend fun save(doc: Doc) = lock.withLock {
        withContext(Dispatchers.IO) { write(doc) }
        _docs.value = (_docs.value.filterNot { it.id == doc.id } + doc).sortedByDescending { it.updated }
    }

    private fun write(doc: Doc) {
        val o = JSONObject().put("id", doc.id).put("name", doc.name).put("kind", doc.kind.name)
            .put("created", doc.created).put("updated", doc.updated)
        val a = JSONArray()
        doc.pages.forEach { p ->
            val po = JSONObject().put("id", p.id).put("orig", p.orig).put("filter", p.filter.name).put("rot", p.rot)
                .put("rendered", p.rendered).put("rw", p.renderW).put("rh", p.renderH)
            p.quad?.let { q -> po.put("quad", JSONArray(q.map { it.toDouble() })) }
            if (p.stamps.isNotEmpty()) po.put("stamps", JSONArray(p.stamps.map { JSONArray().put(it.x.toDouble()).put(it.y.toDouble()).put(it.w.toDouble()) }))
            p.text?.let { po.put("text", it) }
            a.put(po)
        }
        o.put("pages", a)
        val f = File(dir(doc.id).apply { mkdirs() }, "doc.json")
        val tmp = File(f.parentFile, "doc.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    private fun loadAll(): List<Doc> = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
        runCatching {
            val o = JSONObject(File(d, "doc.json").readText())
            val a = o.getJSONArray("pages")
            val pages = (0 until a.length()).map { i ->
                val p = a.getJSONObject(i)
                Page(
                    id = p.getString("id"), orig = p.getString("orig"),
                    filter = runCatching { Filter.valueOf(p.getString("filter")) }.getOrDefault(Filter.AUTO),
                    rot = p.optInt("rot"),
                    quad = p.optJSONArray("quad")?.let { q -> (0 until q.length()).map { q.getDouble(it).toFloat() } },
                    stamps = p.optJSONArray("stamps")?.let { s ->
                        (0 until s.length()).map { s.getJSONArray(it) }.map { Stamp(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
                    } ?: emptyList(),
                    rendered = p.optString("rendered"), renderW = p.optInt("rw"), renderH = p.optInt("rh"),
                    text = if (p.has("text")) p.getString("text") else null,
                )
            }
            Doc(o.getString("id"), o.getString("name"), runCatching { DocKind.valueOf(o.getString("kind")) }.getOrDefault(DocKind.DOC),
                o.optLong("created"), o.optLong("updated"), pages)
        }.onFailure { Log.w("KravosScan", "skip ${d.name}: ${it.message}") }.getOrNull()
    }.sortedByDescending { it.updated }
}
