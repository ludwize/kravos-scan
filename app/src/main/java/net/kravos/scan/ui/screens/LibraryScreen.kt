package net.kravos.scan.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import net.kravos.scan.data.Doc
import net.kravos.scan.data.DocKind
import net.kravos.scan.img.Ops
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.LocalScan
import net.kravos.scan.ui.Route
import net.kravos.scan.ui.ScanJob
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(bannerShown: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scan = LocalScan.current
    val scope = rememberCoroutineScope()
    val docs by app.store.docs.collectAsState()
    val ocr by app.ocr.collectAsState()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var menuDoc by remember { mutableStateOf<Doc?>(null) }
    var renameDoc by remember { mutableStateOf<Doc?>(null) }
    var askId by remember { mutableStateOf(false) }
    var photoTip by remember { mutableStateOf(false) }

    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) docs else docs.filter { it.name.lowercase().contains(q) || it.fullText.lowercase().contains(q) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Kravos Sken", statusPad = !bannerShown) {
            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Pretraga") }
            IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Filled.Settings, "Podešavanja") }
        }
        if (searching) {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Naziv ili tekst iz skena…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            )
        }
        Box(Modifier.weight(1f)) {
            if (docs.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DocumentScanner, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text("Još nema skenova", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Dokument, fotografija ili lična karta — kamera nađe ivice, ispravi perspektivu i očisti sliku. Tekst se prepoznaje (i ćirilica) pa PDF može da se pretražuje.",
                        fontSize = 14.sp, color = LocalPalette.current.dim, lineHeight = 20.sp,
                    )
                }
            } else if (shown.isEmpty()) {
                Text("Ništa za „$query“", Modifier.align(Alignment.Center), color = LocalPalette.current.dim)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { d ->
                        DocTile(d, ocr[d.id], q, onOpen = { nav.go(Route.Document(d.id)) }, onLong = { menuDoc = d })
                    }
                }
            }
        }
        // scan actions
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigAction(Icons.Filled.DocumentScanner, "Dokument", primary = true) { scan.scan(ScanJob.NewDoc) }
            BigAction(Icons.Filled.AutoAwesome, "Foto") { if (app.prefs.photoTipSeen) scan.scan(ScanJob.Photo()) else photoTip = true }
            BigAction(Icons.Filled.Badge, "Lična karta") { askId = true }
            BigAction(Icons.Filled.PhotoLibrary, "Uvoz") { scan.importImages() }
        }
    }

    menuDoc?.let { d ->
        AlertDialog(
            onDismissRequest = { menuDoc = null },
            title = { Text(d.name, maxLines = 2) },
            text = { Text("${d.pages.size} ${pages(d.pages.size)} · ${d.kind.label}") },
            confirmButton = { TextButton(onClick = { renameDoc = d; menuDoc = null }) { Text("Preimenuj") } },
            dismissButton = {
                TextButton(onClick = { app.scope.launch { app.store.delete(d.id) }; menuDoc = null }) { Text("Obriši", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
    renameDoc?.let { d -> RenameDialog(d.name, { renameDoc = null }) { n -> app.scope.launch { app.store.rename(d.id, n) }; renameDoc = null } }

    if (askId) {
        AlertDialog(
            onDismissRequest = { askId = false },
            title = { Text("Koji dokument?") },
            text = {
                Column {
                    Text("Slikaš obe strane, a aplikacija ih slaže u stvarnoj veličini na jednu A4 stranu — spremno za štampu.", fontSize = 13.sp, color = LocalPalette.current.dim)
                    Spacer(Modifier.height(8.dp))
                    Ops.IdKind.entries.forEach { k ->
                        Text(
                            "${k.label}  ·  ${if (k.sides == 2) "2 strane" else "1 strana"}",
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickableCompat {
                                app.prefs.lastIdKind = k.name; askId = false; scan.scan(ScanJob.Id(k))
                            }.padding(vertical = 12.dp, horizontal = 6.dp),
                            fontSize = 16.sp,
                            fontWeight = if (app.prefs.lastIdKind == k.name) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { askId = false }) { Text("Otkaži") } },
        )
    }

    if (photoTip) {
        var dontShow by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { photoTip = false },
            title = { Text("Fotografije bez odsjaja") },
            text = {
                Column {
                    Text(
                        "Slikaj istu fotografiju 3–4 puta. Između snimaka malo pomeri ili nagni telefon da odsjaj pređe na drugo mesto. " +
                            "Aplikacija poravna snimke, izbaci odsjaj i vrati boje izbledelim slikama.\n\nU skeneru izaberi filter „Bez filtera“.",
                        fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = dontShow, onCheckedChange = { dontShow = it })
                        Text("Ne prikazuj ponovo", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { app.prefs.photoTipSeen = dontShow; photoTip = false; scan.scan(ScanJob.Photo()) }) { Text("Počni") } },
            dismissButton = { TextButton(onClick = { photoTip = false }) { Text("Otkaži") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

fun pages(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "strana"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "strane"
    else -> "strana"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocTile(d: Doc, ocrStatus: String?, q: String, onOpen: () -> Unit, onLong: () -> Unit) {
    val app = LocalApp.current
    val pal = LocalPalette.current
    Column(Modifier.clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onOpen, onLongClick = onLong)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(14.dp)).background(pal.card)) {
            val t = d.thumb
            if (t != null) AsyncImage(model = app.store.file(d, t), contentDescription = d.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (d.pages.size > 1) {
                Text(
                    "${d.pages.size}", Modifier.align(Alignment.TopEnd).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(horizontal = 7.dp, vertical = 2.dp),
                    color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            if (d.hasOcr) Icon(Icons.Filled.TextSnippet, "Tekst prepoznat", Modifier.align(Alignment.BottomEnd).padding(6.dp).size(18.dp), tint = Color.White.copy(alpha = 0.85f))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(kindIcon(d.kind), null, Modifier.size(14.dp), tint = pal.dim)
            Spacer(Modifier.width(4.dp))
            Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val sub = ocrStatus ?: if (q.isNotEmpty() && !d.name.lowercase().contains(q)) snippet(d.fullText, q)
        else SimpleDateFormat("d. MMM yyyy.", Locale.forLanguageTag("sr-Latn-RS")).format(Date(d.updated))
        Text(sub, fontSize = 11.sp, color = pal.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

private fun snippet(text: String, q: String): String {
    val i = text.lowercase().indexOf(q)
    if (i < 0) return ""
    val s = (i - 20).coerceAtLeast(0)
    return (if (s > 0) "…" else "") + text.substring(s, (i + q.length + 30).coerceAtMost(text.length)).replace('\n', ' ')
}

fun kindIcon(k: DocKind): ImageVector = when (k) {
    DocKind.DOC -> Icons.Filled.Description
    DocKind.PHOTO -> Icons.Filled.AutoAwesome
    DocKind.ID -> Icons.Filled.Badge
}

@Composable
private fun BigAction(icon: ImageVector, label: String, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(14.dp)).combinedClickableCompat(onClick).padding(4.dp)) {
        Box(Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = fg)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Naziv") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Sačuvaj") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Otkaži") } },
    )
}
