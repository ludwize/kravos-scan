package net.kravos.scan

import android.app.Application
import android.net.Uri
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kravos.scan.data.Doc
import net.kravos.scan.data.DocKind
import net.kravos.scan.data.Http
import net.kravos.scan.data.Prefs
import net.kravos.scan.data.Store
import net.kravos.scan.img.Bitmaps
import net.kravos.scan.img.Filter
import net.kravos.scan.img.Ops
import net.kravos.scan.ocr.Ocr
import java.io.File

class App : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
        private set
    lateinit var store: Store
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Short status shown as an overlay while a scan is being processed ("Spajam snimke…"). */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    /** docId → OCR progress text, while OCR runs. */
    private val _ocr = MutableStateFlow<Map<String, String>>(emptyMap())
    val ocr: StateFlow<Map<String, String>> = _ocr

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    fun toast(s: String?) { _message.value = s }

    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        prefs = Prefs(this)
        store = Store(this, prefs)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
            .crossfade(120)
            .build()

    private suspend fun <T> busy(msg: String, block: suspend () -> T): T? {
        _busy.value = msg
        return try { block() } catch (e: Exception) {
            Log.e(TAG, "$msg failed", e); toast("Greška: ${e.message}"); null
        } finally { _busy.value = null }
    }

    // ------------------------------------------------------------ scan results

    suspend fun newDocument(kind: DocKind, uris: List<Uri>, filter: Filter? = null): Doc? =
        busy("Obrađujem ${uris.size} ${if (uris.size == 1) "stranu" else "strane"}…") {
            store.create(kind, uris, filter ?: prefs.defaultFilter(kind))
        }?.also { autoOcr(it) }

    suspend fun addPages(docId: String, uris: List<Uri>) {
        busy("Dodajem strane…") { store.addPages(docId, uris) }
        store.get(docId)?.let { autoOcr(it) }
    }

    /** Several shots of one print → one glare-free photo. */
    suspend fun photo(uris: List<Uri>, intoDoc: String?): Doc? = busy(if (uris.size > 1) "Spajam ${uris.size} snimka i uklanjam odsjaj…" else "Obrađujem fotografiju…") {
        val img = withContext(Dispatchers.Default) {
            val frames = uris.map { u ->
                val f = File(cacheDir, "frame_${System.nanoTime()}.jpg")
                Bitmaps.copyUri(this@App, u, f)
                Bitmaps.decodeImg(f).also { f.delete() }
            }
            Ops.mergeGlare(frames)
        }
        if (intoDoc != null) { store.addImgPage(intoDoc, img); store.get(intoDoc) }
        else store.createFromImg(DocKind.PHOTO, img, Filter.PHOTO)
    }

    /** Front/back of an ID → true-size A4 page. */
    suspend fun idCard(uris: List<Uri>, kind: Ops.IdKind): Doc? = busy("Slažem ${kind.label.lowercase()} na A4…") {
        val img = withContext(Dispatchers.Default) {
            val sides = uris.map { u ->
                val f = File(cacheDir, "side_${System.nanoTime()}.jpg")
                Bitmaps.copyUri(this@App, u, f)
                Ops.auto(Bitmaps.decodeImg(f, 2400)).also { f.delete() }
            }
            Ops.composeId(sides, kind)
        }
        store.createFromImg(DocKind.ID, img, Filter.ORIGINAL, name = "${kind.label} ${java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date())}")
    }?.also { autoOcr(it) }

    // ------------------------------------------------------------ OCR

    private fun autoOcr(doc: Doc) { if (prefs.autoOcr && doc.kind != DocKind.PHOTO) runOcr(doc.id) }

    fun runOcr(docId: String, force: Boolean = false) {
        if (_ocr.value.containsKey(docId) || !Ocr.available(this)) return
        scope.launch {
            try {
                store.renderAll(docId)
                val doc = store.get(docId) ?: return@launch
                val todo = doc.pages.filter { force || it.text == null }
                todo.forEachIndexed { i, p ->
                    _ocr.value = _ocr.value + (docId to "Prepoznajem tekst ${i + 1}/${todo.size}…")
                    val cur = store.get(docId)?.pages?.firstOrNull { it.id == p.id } ?: return@forEachIndexed
                    if (cur.rendered.isEmpty()) return@forEachIndexed
                    val r = Ocr.recognize(this@App, store.file(docId, cur.rendered), prefs.ocrLangs)
                    // the page may have been edited meanwhile; then its render name changed and we drop the result
                    if (store.get(docId)?.pages?.firstOrNull { it.id == p.id }?.rendered == cur.rendered) store.setOcr(docId, p.id, r.text, r.words)
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e); toast("OCR greška: ${e.message}")
            } finally {
                _ocr.value = _ocr.value - docId
            }
        }
    }

    companion object { const val TAG = "KravosScan" }
}
