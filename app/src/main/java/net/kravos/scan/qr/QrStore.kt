package net.kravos.scan.qr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class QrScan(val id: String, val raw: String, val ts: Long, val product: Boolean) {
    val content: QrContent by lazy { QrParse.parse(raw, product) }
}

/** Scan history (local file, newest first) plus decoding codes from a picture. */
class QrStore(private val ctx: Context) {
    private val file = File(ctx.filesDir, "qr_history.json")
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<QrScan>> = _items

    fun add(raw: String, format: Int): QrScan {
        val product = format in PRODUCT_FORMATS
        // the same code scanned again moves to the top instead of duplicating
        val s = QrScan(UUID.randomUUID().toString().take(10), raw, System.currentTimeMillis(), product)
        _items.value = (listOf(s) + _items.value.filterNot { it.raw == raw }).take(MAX)
        save()
        return s
    }

    fun delete(id: String) { _items.value = _items.value.filterNot { it.id == id }; save() }
    fun clear() { _items.value = emptyList(); save() }

    private fun save() = runCatching {
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().put("id", it.id).put("raw", it.raw).put("ts", it.ts).put("p", it.product)) }
        val tmp = File(file.parentFile, "qr_history.tmp"); tmp.writeText(a.toString()); tmp.renameTo(file)
    }

    private fun load(): List<QrScan> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { QrScan(it.getString("id"), it.getString("raw"), it.getLong("ts"), it.optBoolean("p")) }
    }.getOrDefault(emptyList())

    /** Reads every code in a picture (screenshot, photo). Uses the model bundled in the APK, so it works offline. */
    suspend fun decodeImage(uri: Uri): List<Pair<String, Int>> = withContext(Dispatchers.Default) {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        try {
            scanner.process(InputImage.fromFilePath(ctx, uri)).await()
                .mapNotNull { b -> b.rawValue?.takeIf { it.isNotBlank() }?.let { it to b.format } }
                .distinctBy { it.first }
        } finally { scanner.close() }
    }

    companion object {
        const val MAX = 200
        val PRODUCT_FORMATS = setOf(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
    }
}
