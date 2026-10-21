package net.kravos.scan.ocr

import android.content.Context
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.kravos.scan.data.OcrLang
import net.kravos.scan.img.Bitmaps
import net.kravos.scan.img.Ops
import net.kravos.scan.pdf.Hocr
import net.kravos.scan.pdf.Word
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * On-device OCR with Tesseract (Serbian Cyrillic + Latin + English, "fast" models bundled
 * in the APK). Nothing is sent anywhere.
 */
object Ocr {
    private const val TAG = "KravosScan"
    /** Tesseract likes ~300 dpi text; bigger only costs time. */
    private const val OCR_MAX_SIDE = 3300
    private val mutex = Mutex()

    data class Result(val text: String, val words: List<Word>)

    private fun dataDir(ctx: Context) = File(ctx.filesDir, "ocr")

    /** Copies bundled traineddata to private storage once (Tesseract needs real files). */
    private fun ensureData(ctx: Context): List<String> {
        val td = File(dataDir(ctx), "tessdata").apply { mkdirs() }
        val assets = ctx.assets.list("tessdata")?.toList() ?: emptyList()
        for (a in assets) {
            val dst = File(td, a)
            val size = runCatching { ctx.assets.openFd("tessdata/$a").use { it.length } }.getOrDefault(-1L)
            if (!dst.exists() || (size > 0 && dst.length() != size)) {
                ctx.assets.open("tessdata/$a").use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return td.list()?.filter { it.endsWith(".traineddata") }?.map { it.removeSuffix(".traineddata") } ?: emptyList()
    }

    fun glyphlessFont(ctx: Context): ByteArray? = runCatching { ctx.assets.open("tessdata/pdf.ttf").use { it.readBytes() } }.getOrNull()

    fun available(ctx: Context): Boolean = runCatching { ctx.assets.list("tessdata")?.any { it.endsWith(".traineddata") } == true }.getOrDefault(false)

    suspend fun recognize(ctx: Context, file: File, langs: Set<OcrLang>): Result = mutex.withLock {
        withContext(Dispatchers.Default) {
            val have = ensureData(ctx)
            val use = OcrLang.entries.filter { it in langs && it.code in have }.map { it.code }
                .ifEmpty { have.take(1) }
            if (use.isEmpty()) error("OCR podaci nisu u aplikaciji")
            var img = Bitmaps.decodeImg(file)
            val scale = if (max(img.w, img.h) > OCR_MAX_SIDE) OCR_MAX_SIDE.toFloat() / max(img.w, img.h) else 1f
            if (scale < 1f) img = Ops.fitMax(img, OCR_MAX_SIDE)
            val bm = Bitmaps.toBitmap(img)
            val api = TessBaseAPI()
            try {
                if (!api.init(dataDir(ctx).path, use.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) error("Tesseract init nije uspeo")
                api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                api.setVariable("user_defined_dpi", "300")
                api.setImage(bm)
                val text = api.getUTF8Text().orEmpty().trim()
                val words = Hocr.parse(api.getHOCRText(0).orEmpty()).map {
                    if (scale == 1f) it else it.copy(
                        x0 = (it.x0 / scale).roundToInt(), y0 = (it.y0 / scale).roundToInt(),
                        x1 = (it.x1 / scale).roundToInt(), y1 = (it.y1 / scale).roundToInt(),
                    )
                }
                Log.i(TAG, "OCR ${file.name}: ${text.length} chars, ${words.size} words, langs=$use")
                Result(text, words)
            } finally {
                api.recycle(); bm.recycle()
            }
        }
    }
}
