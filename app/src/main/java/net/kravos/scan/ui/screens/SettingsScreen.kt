package net.kravos.scan.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import net.kravos.scan.BuildConfig
import net.kravos.scan.data.OcrLang
import net.kravos.scan.data.Quality
import net.kravos.scan.data.ThemeMode
import net.kravos.scan.img.Filter
import net.kravos.scan.ocr.Ocr
import net.kravos.scan.pdf.PageSize
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.Route
import net.kravos.scan.ui.components.CardTitle
import net.kravos.scan.ui.components.KCard
import net.kravos.scan.ui.components.Pill
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import net.kravos.scan.update.Release
import net.kravos.scan.update.Updater

@Composable
fun SettingsScreen(release: Release?, onCheckUpdate: () -> Unit) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by app.prefs.theme.collectAsState()
    val pal = LocalPalette.current
    var progress by remember { mutableStateOf<Float?>(null) }
    var updMsg by remember { mutableStateOf<String?>(null) }
    var auto by remember { mutableStateOf(app.prefs.autoUpdateCheck) }
    var docFilter by remember { mutableStateOf(app.prefs.docFilter) }
    var pageSize by remember { mutableStateOf(app.prefs.pageSize) }
    var quality by remember { mutableStateOf(app.prefs.quality) }
    var autoOcr by remember { mutableStateOf(app.prefs.autoOcr) }
    var langs by remember { mutableStateOf(app.prefs.ocrLangs) }
    var sigVer by remember { mutableIntStateOf(0) }

    @Composable
    fun ChoiceRow(content: @Composable () -> Unit) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 2.dp)) { content() }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Podešavanja", onBack = { nav.back() })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                KCard {
                    CardTitle("Ažuriranje")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Verzija ${BuildConfig.VERSION_NAME}", fontSize = 15.sp)
                            Text(
                                when {
                                    release == null -> "Nije provereno"
                                    Updater.isNewer(release) -> "Dostupna je verzija 1.0.${release.build}"
                                    else -> "Imaš najnoviju verziju"
                                }, fontSize = 12.sp, color = pal.dim,
                            )
                        }
                        if (release != null && Updater.isNewer(release)) {
                            Button(enabled = progress == null, onClick = {
                                scope.launch {
                                    try {
                                        progress = 0f
                                        val f = Updater.download(ctx, release) { progress = it }
                                        progress = null
                                        if (!Updater.install(ctx, f)) updMsg = "Dozvoli instalaciju iz ove aplikacije, pa ponovo pritisni Instaliraj."
                                    } catch (e: Exception) {
                                        progress = null; updMsg = "Greška: ${e.message}"
                                    }
                                }
                            }) { Text("Instaliraj") }
                        } else TextButton(onClick = onCheckUpdate) { Text("Proveri") }
                    }
                    progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(14.dp)) }
                    updMsg?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp)) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Automatski proveri pri pokretanju", Modifier.weight(1f), fontSize = 14.sp)
                        Switch(checked = auto, onCheckedChange = { auto = it; app.prefs.autoUpdateCheck = it })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                KCard {
                    CardTitle("Skeniranje")
                    Text("Podrazumevani filter za dokumente", Modifier.padding(horizontal = 14.dp), fontSize = 14.sp)
                    ChoiceRow {
                        listOf(Filter.MAGIC, Filter.AUTO, Filter.GRAY, Filter.BW, Filter.ORIGINAL).forEach { f ->
                            Pill(f.label, docFilter == f, { docFilter = f; app.prefs.docFilter = f })
                        }
                    }
                    Text("Kvalitet slike", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 14.sp)
                    ChoiceRow { Quality.entries.forEach { q -> Pill(q.label, quality == q, { quality = q; app.prefs.quality = q }) } }
                    Text("Veličina PDF strane", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 14.sp)
                    ChoiceRow { PageSize.entries.forEach { s -> Pill(s.label, pageSize == s, { pageSize = s; app.prefs.pageSize = s }) } }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                KCard {
                    CardTitle("Prepoznavanje teksta (OCR)")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatski posle skeniranja", fontSize = 14.sp)
                            Text("Radi na telefonu, bez interneta", fontSize = 12.sp, color = pal.dim)
                        }
                        Switch(checked = autoOcr, onCheckedChange = { autoOcr = it; app.prefs.autoOcr = it })
                    }
                    OcrLang.entries.forEach { l ->
                        Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = l in langs, onCheckedChange = { c ->
                                val n = if (c) langs + l else langs - l
                                if (n.isNotEmpty()) { langs = n; app.prefs.ocrLangs = n }
                            })
                            Text(l.label, fontSize = 14.sp)
                        }
                    }
                    if (!Ocr.available(ctx)) Text("OCR podaci nedostaju u ovoj verziji.", Modifier.padding(14.dp), fontSize = 12.sp, color = Color(0xFFFF6B6B))
                    Text("Manje jezika = brže prepoznavanje.", Modifier.padding(horizontal = 14.dp, vertical = 4.dp), fontSize = 12.sp, color = pal.dim)
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                KCard {
                    CardTitle("Potpis")
                    val f = app.store.signatureFile()
                    if (f.exists()) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(f).memoryCacheKey("${f.path}:${f.lastModified()}:$sigVer").build(),
                                contentDescription = "Potpis", modifier = Modifier.fillMaxWidth().height(80.dp),
                            )
                        }
                    } else Text("Još nema sačuvanog potpisa.", Modifier.padding(horizontal = 14.dp), fontSize = 13.sp, color = pal.dim)
                    Row(Modifier.padding(horizontal = 6.dp)) {
                        TextButton(onClick = { nav.go(Route.Signature(null)) }) { Text(if (f.exists()) "Nacrtaj novi" else "Nacrtaj") }
                        if (f.exists()) TextButton(onClick = { f.delete(); sigVer++ }) { Text("Obriši") }
                    }
                }
            }
            item {
                KCard {
                    CardTitle("Tema")
                    ChoiceRow {
                        listOf(ThemeMode.DARK to "Tamna", ThemeMode.LIGHT to "Svetla", ThemeMode.SYSTEM to "Sistem").forEach { (m, l) ->
                            Pill(l, theme == m, { app.prefs.setTheme(m) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                KCard {
                    CardTitle("O aplikaciji")
                    Text(
                        "Skener: Google ML Kit (ivice, perspektiva, uklanjanje senki). OCR: Tesseract — srpska ćirilica, latinica i engleski. " +
                            "Svi skenovi ostaju na telefonu; ništa se ne šalje dok sam ne izvezeš.",
                        fontSize = 12.sp, color = pal.dim, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().size(1.dp)) }
        }
    }
}
