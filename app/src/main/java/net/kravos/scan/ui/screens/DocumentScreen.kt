package net.kravos.scan.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.kravos.scan.data.Doc
import net.kravos.scan.data.DocKind
import net.kravos.scan.data.Page
import net.kravos.scan.data.Stamp
import net.kravos.scan.export.ExportFormat
import net.kravos.scan.export.Exporter
import net.kravos.scan.img.Filter
import net.kravos.scan.ocr.Ocr
import net.kravos.scan.pdf.PageSize
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.LocalScan
import net.kravos.scan.ui.Route
import net.kravos.scan.ui.ScanJob
import net.kravos.scan.ui.components.Pill
import net.kravos.scan.ui.components.ToolButton
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import kotlin.math.roundToInt

/** Set by the signature screen so the document opens straight into placement. */
object PendingSign { var docId: String? = null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocumentScreen(docId: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scan = LocalScan.current
    val scope = rememberCoroutineScope()
    val docs by app.store.docs.collectAsState()
    val ocrState by app.ocr.collectAsState()
    val doc = docs.firstOrNull { it.id == docId }
    if (doc == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val pager = rememberPagerState { doc.pages.size }
    val page = doc.pages.getOrNull(pager.currentPage.coerceAtMost(doc.pages.lastIndex))
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var signing by remember { mutableStateOf(PendingSign.docId == docId && app.store.signatureFile().exists()) }
    LaunchedEffect(Unit) { PendingSign.docId = null }
    var zoomed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(doc.name, onBack = { nav.back() }, onTitle = { rename = true }) {
            IconButton(onClick = { showExport = true }) { Icon(Icons.Filled.Share, "Izvezi") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Još") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Preimenuj") }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text("Prepoznaj tekst ponovo") }, onClick = { menu = false; app.runOcr(doc.id, force = true) })
                    DropdownMenuItem(text = { Text("Dodaj strane iz galerije") }, onClick = { menu = false; scan.importImages(doc.id) })
                    if (page != null && page.stamps.isNotEmpty())
                        DropdownMenuItem(text = { Text("Ukloni potpis sa strane") }, onClick = { menu = false; app.scope.launch { app.store.setStamps(doc.id, page.id, emptyList()) } })
                    DropdownMenuItem(text = { Text("Obriši dokument", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }

        // ---- pages
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed && !signing, beyondViewportPageCount = 1, key = { doc.pages.getOrNull(it)?.id ?: it }) { i ->
                val p = doc.pages.getOrNull(i)
                if (p != null) PageView(doc, p, onZoom = { zoomed = it }, signing = signing && i == pager.currentPage, onSigned = { st ->
                    signing = false
                    if (st != null) app.scope.launch { app.store.setStamps(doc.id, p.id, p.stamps + st) }
                })
            }
            val ocrMsg = ocrState[doc.id]
            Row(Modifier.align(Alignment.TopCenter).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (doc.pages.size > 1) Chip("${pager.currentPage + 1} / ${doc.pages.size}")
                if (ocrMsg != null) Chip(ocrMsg)
            }
        }

        if (!signing && page != null) {
            // ---- filters
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Filter.entries.forEach { f ->
                    Pill(f.label, page.filter == f, { app.scope.launch { app.store.setFilter(doc.id, page.id, f) } })
                }
                if (doc.pages.size > 1) TextButton(onClick = { app.scope.launch { app.store.setFilter(doc.id, null, page.filter) } }) { Text("Na sve strane", fontSize = 12.sp) }
            }
            // ---- tools
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding()
                    .horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ToolButton(Icons.AutoMirrored.Filled.RotateRight, "Rotiraj") { app.scope.launch { app.store.rotate(doc.id, page.id, 90) } }
                ToolButton(Icons.Filled.Crop, "Iseci") { nav.go(Route.Crop(doc.id, page.id)) }
                ToolButton(Icons.Filled.Draw, "Potpis") {
                    if (app.store.signatureFile().exists()) signing = true else { PendingSign.docId = doc.id; nav.go(Route.Signature(doc.id)) }
                }
                ToolButton(Icons.Filled.TextFields, "Tekst", enabled = doc.kind != DocKind.PHOTO || page.text != null) { showText = true }
                ToolButton(Icons.Filled.AddPhotoAlternate, "Dodaj") {
                    scan.scan(if (doc.kind == DocKind.PHOTO) ScanJob.Photo(doc.id) else ScanJob.AddPages(doc.id))
                }
                ToolButton(Icons.Filled.ChevronLeft, "Napred", enabled = pager.currentPage > 0) {
                    scope.launch { app.store.movePage(doc.id, page.id, -1); pager.scrollToPage(pager.currentPage - 1) }
                }
                ToolButton(Icons.Filled.ChevronRight, "Nazad", enabled = pager.currentPage < doc.pages.lastIndex) {
                    scope.launch { app.store.movePage(doc.id, page.id, 1); pager.scrollToPage(pager.currentPage + 1) }
                }
                ToolButton(Icons.Filled.Delete, "Obriši") { app.scope.launch { app.store.deletePage(doc.id, page.id) } }
            }
        }
    }

    if (rename) RenameDialog(doc.name, { rename = false }) { n -> app.scope.launch { app.store.rename(doc.id, n) }; rename = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Obrisati „${doc.name}“?") },
            text = { Text("Brišu se sve strane. Ovo ne može da se vrati.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; nav.back(); app.scope.launch { app.store.delete(doc.id) } }) { Text("Obriši", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Otkaži") } },
        )
    }
    if (showText && page != null) TextSheet(doc, page, ocrState[doc.id]) { showText = false }
    if (showExport) ExportSheet(doc) { showExport = false }
}

@Composable
private fun Chip(t: String) {
    Text(t, Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xB3000000)).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontSize = 12.sp)
}

/** One page: pinch/double-tap zoom, or signature placement on top of it. */
@Composable
private fun PageView(doc: Doc, p: Page, onZoom: (Boolean) -> Unit, signing: Boolean, onSigned: (Stamp?) -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var scale by remember(p.rendered) { mutableFloatStateOf(1f) }
    var off by remember(p.rendered) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
        if (p.rendered.isEmpty()) {
            CircularProgressIndicator()
            return@BoxWithConstraints
        }
        val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
        val fit = minOf(cw / p.renderW.coerceAtLeast(1), ch / p.renderH.coerceAtLeast(1))
        val dw = p.renderW * fit; val dh = p.renderH * fit
        val left = (cw - dw) / 2; val top = (ch - dh) / 2
        AsyncImage(
            model = app.store.file(doc, p.rendered), contentDescription = "Strana",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y }
                .pointerInput(p.rendered, signing) {
                    if (signing) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f
                        onZoom(scale > 1f)
                    })
                }
                .pointerInput(p.rendered, signing) {
                    if (signing) return@pointerInput
                    detectTransformGestures { _, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(1f, 6f)
                        scale = ns
                        off = if (ns == 1f) Offset.Zero else Offset(
                            (off.x + pan.x).coerceIn(-cw * (ns - 1) / 2, cw * (ns - 1) / 2),
                            (off.y + pan.y).coerceIn(-ch * (ns - 1) / 2, ch * (ns - 1) / 2),
                        )
                        onZoom(ns > 1f)
                    }
                },
        )
        if (signing) {
            val sigFile = app.store.signatureFile()
            val aspect = remember(sigFile.lastModified()) {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(sigFile.path, o)
                if (o.outWidth > 0) o.outHeight.toFloat() / o.outWidth else 0.4f
            }
            var sw by remember { mutableFloatStateOf(0.32f) }
            var sx by remember { mutableFloatStateOf(0.6f) }
            var sy by remember { mutableFloatStateOf(0.8f) }
            val boxW = dw * sw; val boxH = boxW * aspect * (1f) // display px
            val density = LocalDensity.current
            Box(
                Modifier.offset { IntOffset((left + sx * dw).roundToInt(), (top + sy * dh).roundToInt()) }
                    .align(Alignment.TopStart)
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { ch2, d ->
                            ch2.consume()
                            sx = (sx + d.x / dw).coerceIn(0f, 1f - sw)
                            sy = (sy + d.y / dh).coerceIn(0f, 1f - boxH / dh)
                        }
                    },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(sigFile).memoryCacheKey("${sigFile.path}:${sigFile.lastModified()}").build(),
                    contentDescription = "Potpis", modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            ) {
                Text("Prevuci potpis na mesto. Veličina:", fontSize = 13.sp)
                Slider(value = sw, onValueChange = { sw = it; sx = sx.coerceAtMost(1f - it) }, valueRange = 0.1f..0.7f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSigned(null) }) { Text("Otkaži") }
                    val nav = LocalNav.current
                    OutlinedButton(onClick = { onSigned(null); PendingSign.docId = doc.id; nav.go(Route.Signature(doc.id)) }) { Text("Novi potpis") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSigned(Stamp(sx, sy, sw)) }) { Text("Postavi") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSheet(doc: Doc, page: Page, ocrMsg: String?, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Prepoznat tekst", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (doc.pages.size > 1) {
                    Pill("Ova strana", !all, { all = false }); Pill("Sve", all, { all = true })
                }
            }
            val text = if (all) doc.fullText else page.text
            when {
                ocrMsg != null -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text(ocrMsg)
                }
                !Ocr.available(ctx) -> Text("OCR podaci nisu u ovoj verziji aplikacije.", Modifier.padding(vertical = 24.dp))
                text == null || (!all && page.text == null) -> Column(Modifier.padding(vertical = 16.dp)) {
                    Text("Tekst još nije prepoznat.", color = LocalPalette.current.dim)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { app.runOcr(doc.id) }) { Text("Prepoznaj tekst") }
                }
                text.isBlank() -> Text("Na strani nije pronađen tekst.", Modifier.padding(vertical = 24.dp), color = LocalPalette.current.dim)
                else -> {
                    Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        SelectionContainer { Text(text, fontSize = 15.sp, lineHeight = 21.sp) }
                    }
                    FilledTonalButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(doc.name, text))
                        app.toast("Tekst kopiran")
                    }) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Kopiraj") }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(doc: Doc, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocrState by app.ocr.collectAsState()
    var fmt by remember { mutableStateOf(if (doc.kind == DocKind.PHOTO) ExportFormat.JPG else ExportFormat.PDF) }
    var size by remember { mutableStateOf(app.prefs.pageSize) }
    var withText by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    val ocrRunning = ocrState.containsKey(doc.id)

    fun go(dest: String) {
        if (working) return
        working = true
        scope.launch {
            try {
                val files = Exporter.build(ctx, app.store, doc, fmt, size, withText && doc.hasOcr)
                when (dest) {
                    "share" -> Exporter.share(ctx, files, fmt, doc.name)
                    "drive" -> if (!Exporter.toDrive(ctx, files, fmt, doc.name)) Exporter.share(ctx, files, fmt, doc.name)
                    "save" -> app.toast(Exporter.toDownloads(ctx, files, fmt))
                }
                onDismiss()
            } catch (e: Exception) {
                app.toast("Izvoz nije uspeo: ${e.message}")
            } finally { working = false }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text("Izvoz", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ExportFormat.entries.forEach { f ->
                    Pill(f.label, fmt == f, { fmt = f }, enabled = f != ExportFormat.TXT || doc.hasOcr)
                }
            }
            if (fmt == ExportFormat.PDF) {
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Strana:", fontSize = 13.sp, color = LocalPalette.current.dim, modifier = Modifier.padding(end = 4.dp))
                    PageSize.entries.forEach { s -> Pill(s.label, size == s, { size = s; app.prefs.pageSize = s }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = withText && doc.hasOcr, enabled = doc.hasOcr, onCheckedChange = { withText = it })
                    Column(Modifier.weight(1f)) {
                        Text("Pretraživ PDF (sloj teksta)", fontSize = 14.sp)
                        Text(
                            when {
                                doc.hasOcr -> "Tekst može da se traži i kopira u PDF-u"
                                ocrRunning -> ocrState[doc.id] ?: ""
                                else -> "Tekst još nije prepoznat"
                            }, fontSize = 12.sp, color = LocalPalette.current.dim,
                        )
                    }
                    if (!doc.hasOcr && !ocrRunning && Ocr.available(ctx)) TextButton(onClick = { app.runOcr(doc.id) }) { Text("Prepoznaj") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { go("share") }, enabled = !working, modifier = Modifier.weight(1f)) { Text("Podeli") }
                FilledTonalButton(onClick = { go("save") }, enabled = !working, modifier = Modifier.weight(1f)) { Text("Preuzimanja") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { go("drive") }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                Text(if (Exporter.hasDrive(ctx)) "Sačuvaj na Google Drive" else "Google Drive (aplikacija nije instalirana)")
            }
            if (working) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Pravim fajl…", fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
