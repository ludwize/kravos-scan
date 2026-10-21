package net.kravos.scan.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kravos.scan.data.Doc
import net.kravos.scan.data.Store
import net.kravos.scan.ocr.Ocr
import net.kravos.scan.pdf.PageSize
import net.kravos.scan.pdf.PdfPage
import net.kravos.scan.pdf.PdfWriter
import java.io.File

enum class ExportFormat(val label: String, val mime: String, val ext: String) {
    PDF("PDF", "application/pdf", "pdf"),
    JPG("JPG slike", "image/jpeg", "jpg"),
    TXT("Tekst (TXT)", "text/plain", "txt"),
}

object Exporter {
    const val DRIVE_PKG = "com.google.android.apps.docs"

    private fun outDir(ctx: Context) = File(ctx.cacheDir, "export").apply { mkdirs() }

    fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|]"""), "_").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Sken" }

    /** Builds the export files in cache; returns them (one PDF/TXT, or one JPG per page). */
    suspend fun build(ctx: Context, store: Store, doc: Doc, fmt: ExportFormat, size: PageSize, withText: Boolean): List<File> =
        withContext(Dispatchers.IO) {
            val dir = outDir(ctx); dir.listFiles()?.forEach { it.delete() }
            val base = safeName(doc.name)
            when (fmt) {
                ExportFormat.PDF -> {
                    val pages = doc.pages.filter { it.rendered.isNotEmpty() }.map { p ->
                        val f = store.file(doc, p.rendered)
                        PdfPage(f.readBytes(), p.renderW, p.renderH, dpi = 300, words = if (withText) store.words(doc.id, p) else emptyList())
                    }
                    val out = File(dir, "$base.pdf")
                    val font = if (withText) Ocr.glyphlessFont(ctx) else null
                    out.outputStream().use { PdfWriter(size, font, doc.name).write(pages, it) }
                    listOf(out)
                }
                ExportFormat.JPG -> doc.pages.filter { it.rendered.isNotEmpty() }.mapIndexed { i, p ->
                    val out = File(dir, if (doc.pages.size == 1) "$base.jpg" else "$base-${i + 1}.jpg")
                    store.file(doc, p.rendered).copyTo(out, overwrite = true)
                }
                ExportFormat.TXT -> {
                    val out = File(dir, "$base.txt")
                    out.writeText(doc.fullText)
                    listOf(out)
                }
            }
        }

    private fun uris(ctx: Context, files: List<File>) = files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }

    private fun sendIntent(ctx: Context, files: List<File>, fmt: ExportFormat, title: String): Intent {
        val u = uris(ctx, files)
        val i = if (u.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, u[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(u))
        i.type = fmt.mime
        i.putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TITLE, title)
        i.clipData = ClipData.newRawUri(title, u[0]).apply { u.drop(1).forEach { addItem(ClipData.Item(it)) } }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return i
    }

    fun share(ctx: Context, files: List<File>, fmt: ExportFormat, title: String) {
        ctx.startActivity(Intent.createChooser(sendIntent(ctx, files, fmt, title), "Podeli").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun hasDrive(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(DRIVE_PKG, 0); true }.getOrDefault(false)

    /** Opens Google Drive's own "Save to Drive" screen (choose account and folder there). */
    fun toDrive(ctx: Context, files: List<File>, fmt: ExportFormat, title: String): Boolean {
        val i = sendIntent(ctx, files, fmt, title).setPackage(DRIVE_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(i); true }.getOrDefault(false)
    }

    /** Saves into Downloads/Kravos Sken (visible in the Files app). Returns a human description. */
    suspend fun toDownloads(ctx: Context, files: List<File>, fmt: ExportFormat): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kravos Sken").apply { mkdirs() }
            files.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            return@withContext "Sačuvano u Preuzimanja/Kravos Sken"
        }
        val cr = ctx.contentResolver
        for (f in files) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, fmt.mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kravos Sken")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("Ne mogu da upišem u Preuzimanja")
            cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        if (files.size == 1) "Sačuvano: Preuzimanja/Kravos Sken/${files[0].name}" else "Sačuvano ${files.size} fajla u Preuzimanja/Kravos Sken"
    }
}
