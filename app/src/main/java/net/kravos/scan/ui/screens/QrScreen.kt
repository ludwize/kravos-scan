package net.kravos.scan.ui.screens

import android.content.Context
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.kravos.scan.App
import net.kravos.scan.qr.QrActions
import net.kravos.scan.qr.QrContent
import net.kravos.scan.qr.QrScan
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Set by the smoke test / share hooks to open a result straight away. */
object PendingQr { var id: String? = null }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QrScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by app.qr.items.collectAsState()
    var shown by remember { mutableStateOf<QrScan?>(null) }
    var askDelete by remember { mutableStateOf<QrScan?>(null) }
    var askClear by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { PendingQr.id?.let { id -> shown = items.firstOrNull { it.id == id }; PendingQr.id = null } }

    fun handle(s: QrScan) {
        val c = s.content
        if (app.prefs.qrAutoOpen && c is QrContent.Link && c.check.safe && QrActions.open(ctx, c.url)) return
        shown = s
    }

    fun scanCamera() {
        scope.launch {
            val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).enableAutoZoom().build()
            val scanner = GmsBarcodeScanning.getClient(ctx, opts)
            try {
                // sideloaded apps don't get the scanner UI module at install time; ask Play services for it once
                val mi = ModuleInstall.getClient(ctx)
                if (!mi.areModulesAvailable(scanner).await().areModulesAvailable()) {
                    app.toast("Preuzimam QR skener od Google-a, sačekaj par sekundi…")
                    mi.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build()).await()
                }
                val b = scanner.startScan().await()
                val raw = b.rawValue
                if (raw.isNullOrBlank()) app.toast("Kod je prazan") else handle(app.qr.add(raw, b.format))
            } catch (e: CancellationException) {
                // user closed the scanner
            } catch (e: Exception) {
                Log.w(App.TAG, "QR scan failed", e)
                app.toast("QR skener nije dostupan (${e.message}). Pokušaj ponovo za par sekundi ili učitaj sliku.")
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        scope.launch {
            try {
                val codes = app.qr.decodeImage(uri)
                if (codes.isEmpty()) app.toast("Na slici nema QR ni bar koda")
                else {
                    val added = codes.reversed().map { (raw, f) -> app.qr.add(raw, f) }.reversed()
                    if (added.size > 1) app.toast("Pronađeno ${added.size} koda — svi su u istoriji")
                    handle(added.first())
                }
            } catch (e: Exception) {
                app.toast("Ne mogu da pročitam sliku: ${e.message}")
            } finally { working = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("QR kodovi", onBack = { nav.back() }) {
            if (items.isNotEmpty()) TextButton(onClick = { askClear = true }) { Text("Obriši sve") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { scanCamera() }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Skeniraj", fontSize = 16.sp)
            }
            FilledTonalButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !working, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(16.dp),
            ) { Icon(Icons.Filled.Image, null); Spacer(Modifier.width(8.dp)); Text(if (working) "Čitam…" else "Iz slike", fontSize = 16.sp) }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.QrCodeScanner, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))
                Text("Nema skeniranih kodova", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "QR i bar kodovi — linkovi, Wi-Fi, IPS uplatnice sa računa, kontakti. „Iz slike“ čita kod sa screenshota ili fotografije. " +
                        "Pre otvaranja linka vidiš na koji sajt zaista vodi.",
                    fontSize = 14.sp, color = LocalPalette.current.dim, lineHeight = 20.sp,
                )
            }
        } else {
            Text("Istorija", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                items(items, key = { it.id }) { s ->
                    val c = s.content
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(LocalPalette.current.card)
                            .combinedClickable(onClick = { shown = s }, onLongClick = { askDelete = s }).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val warn = c is QrContent.Link && !c.check.safe
                        Icon(if (warn) Icons.Filled.Warning else iconOf(c), null, Modifier.size(26.dp), tint = if (warn) Warn else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${c.label} · ${SimpleDateFormat("d. MMM HH:mm", Locale.forLanguageTag("sr-Latn-RS")).format(Date(s.ts))}",
                                fontSize = 12.sp, color = LocalPalette.current.dim,
                            )
                        }
                    }
                }
            }
        }
    }

    shown?.let { s -> QrResultSheet(s) { shown = null } }
    askDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("Obrisati iz istorije?") },
            text = { Text(s.content.title, maxLines = 3) },
            confirmButton = { TextButton(onClick = { app.qr.delete(s.id); askDelete = null }) { Text("Obriši", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Otkaži") } },
        )
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Obrisati celu istoriju?") },
            confirmButton = { TextButton(onClick = { app.qr.clear(); askClear = false }) { Text("Obriši", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("Otkaži") } },
        )
    }
}

private val Warn = Color(0xFFFFB020)

private fun iconOf(c: QrContent): ImageVector = when (c) {
    is QrContent.Link -> Icons.Filled.Link
    is QrContent.Wifi -> Icons.Filled.Wifi
    is QrContent.Ips -> Icons.Filled.Payments
    is QrContent.Phone -> Icons.Filled.Call
    is QrContent.Sms -> Icons.Filled.Sms
    is QrContent.Email -> Icons.Filled.Email
    is QrContent.Geo -> Icons.Filled.LocationOn
    is QrContent.Contact -> Icons.Filled.Person
    is QrContent.Event -> Icons.Filled.CalendarMonth
    is QrContent.Text -> if (c.product) Icons.Filled.ShoppingCart else Icons.Filled.TextSnippet
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QrResultSheet(s: QrScan, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val ctx = LocalContext.current
    val c = s.content
    val pal = LocalPalette.current
    fun copy(label: String, v: String) { QrActions.copy(ctx, label, v); app.toast("Kopirano: $label") }
    fun fail(ok: Boolean) { if (!ok) app.toast("Nema aplikacije koja ovo može da otvori") }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(iconOf(c), null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(c.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            when (c) {
                is QrContent.Link -> LinkBody(c)
                is QrContent.Wifi -> {
                    Field("Mreža", c.ssid); Field("Lozinka", c.password ?: "—", mono = true); Field("Zaštita", c.security + if (c.hidden) " · skrivena" else "")
                }
                is QrContent.Ips -> {
                    Field("Primalac", c.payee)
                    Field("Račun", c.account, mono = true)
                    c.amount?.let { Field("Iznos", "$it ${c.currency ?: ""}".trim()) }
                    c.purpose?.let { Field("Svrha", it) }
                    c.code?.let { Field("Šifra plaćanja", it) }
                    c.reference?.let { Field("Poziv na broj", it, mono = true) }
                    c.payer?.let { Field("Platilac", it) }
                    Text("Platiš je skeniranjem istog koda u aplikaciji svoje banke (IPS skeniraj), ili prekucaš podatke odavde.", fontSize = 12.sp, color = pal.dim, lineHeight = 17.sp)
                }
                is QrContent.Phone -> Field("Broj", c.number)
                is QrContent.Sms -> { Field("Broj", c.number); c.body?.let { Field("Poruka", it) } }
                is QrContent.Email -> { Field("Za", c.to); c.subject?.let { Field("Naslov", it) }; c.body?.let { Field("Tekst", it) } }
                is QrContent.Geo -> { c.query?.let { Field("Mesto", it) }; Field("Koordinate", "%.6f, %.6f".format(Locale.ROOT, c.lat, c.lon), mono = true) }
                is QrContent.Contact -> {
                    c.name?.let { Field("Ime", it) }; c.org?.let { Field("Firma", it) }
                    c.phones.forEach { Field("Telefon", it) }; c.emails.forEach { Field("Mejl", it) }
                    c.address?.let { Field("Adresa", it) }; c.url?.let { Field("Sajt", it) }
                }
                is QrContent.Event -> {
                    c.title?.let { Field("Naziv", it) }
                    val df = SimpleDateFormat("EEE d. MMM yyyy. HH:mm", Locale.forLanguageTag("sr-Latn-RS"))
                    c.startMs?.let { Field("Početak", df.format(Date(it))) }; c.endMs?.let { Field("Kraj", df.format(Date(it))) }
                    c.location?.let { Field("Mesto", it) }; c.description?.let { Field("Opis", it) }
                }
                is QrContent.Text -> SelectionContainer { Text(c.text, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = if (c.product) FontFamily.Monospace else FontFamily.Default) }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (c) {
                    is QrContent.Link -> {
                        if (c.check.safe) Button(onClick = { fail(QrActions.open(ctx, c.url)); onDismiss() }) { Text("Otvori") }
                        else OutlinedButton(onClick = { fail(QrActions.open(ctx, c.url)); onDismiss() }) { Text("Ipak otvori", color = Warn) }
                        FilledTonalButton(onClick = { copy("link", c.url) }) { Text("Kopiraj") }
                        FilledTonalButton(onClick = { fail(QrActions.share(ctx, c.url)) }) { Text("Podeli") }
                    }
                    is QrContent.Wifi -> {
                        Button(onClick = { fail(QrActions.joinWifi(ctx, c)) }) { Text("Poveži se") }
                        c.password?.let { p -> FilledTonalButton(onClick = { copy("lozinka", p) }) { Text("Kopiraj lozinku") } }
                    }
                    is QrContent.Ips -> {
                        Button(onClick = { copy("račun", c.account) }) { Text("Kopiraj račun") }
                        c.amount?.let { a -> FilledTonalButton(onClick = { copy("iznos", a) }) { Text("Kopiraj iznos") } }
                        c.reference?.let { r -> FilledTonalButton(onClick = { copy("poziv na broj", r.substringAfter(") ")) }) { Text("Kopiraj poziv na broj") } }
                    }
                    is QrContent.Phone -> {
                        Button(onClick = { fail(QrActions.dial(ctx, c.number)) }) { Text("Pozovi") }
                        FilledTonalButton(onClick = { fail(QrActions.addContact(ctx, QrContent.Contact(null, null, listOf(c.number), emptyList(), null, null))) }) { Text("Dodaj u kontakte") }
                    }
                    is QrContent.Sms -> Button(onClick = { fail(QrActions.sms(ctx, c.number, c.body)) }) { Text("Pošalji SMS") }
                    is QrContent.Email -> Button(onClick = { fail(QrActions.email(ctx, c.to, c.subject, c.body)) }) { Text("Napiši mejl") }
                    is QrContent.Geo -> Button(onClick = { fail(QrActions.map(ctx, c)) }) { Text("Otvori mapu") }
                    is QrContent.Contact -> Button(onClick = { fail(QrActions.addContact(ctx, c)) }) { Text("Dodaj u kontakte") }
                    is QrContent.Event -> Button(onClick = { fail(QrActions.addEvent(ctx, c)) }) { Text("Dodaj u kalendar") }
                    is QrContent.Text -> {
                        Button(onClick = { fail(QrActions.webSearch(ctx, c.text)) }) { Text(if (c.product) "Potraži proizvod" else "Potraži na webu") }
                        FilledTonalButton(onClick = { copy("tekst", c.text) }) { Text("Kopiraj") }
                    }
                }
                if (c !is QrContent.Link && c !is QrContent.Text) TextButton(onClick = { copy("sadržaj koda", s.raw) }) { Text("Kopiraj sirovo") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Shows where the link really goes: the registrable domain in bold, red flags above the buttons. */
@Composable
private fun LinkBody(c: QrContent.Link) {
    val pal = LocalPalette.current
    val k = c.check
    Text("Vodi na", fontSize = 12.sp, color = pal.dim)
    val sub = k.host.removeSuffix(k.domain)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = pal.dim)) { append(sub) }
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(k.domain) }
        },
        fontSize = 24.sp,
    )
    Spacer(Modifier.height(6.dp))
    SelectionContainer { Text(c.url, fontSize = 13.sp, color = pal.dim, fontFamily = FontFamily.Monospace, lineHeight = 18.sp) }
    Spacer(Modifier.height(10.dp))
    if (k.safe) {
        Text("✓ Nema uobičajenih znakova prevare. Ipak, proveri da li je ovo sajt koji očekuješ.", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, lineHeight = 18.sp)
    } else {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Warn.copy(alpha = 0.14f)).padding(12.dp)) {
            k.warnings.forEach { w ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = Warn)
                    Spacer(Modifier.width(8.dp))
                    Text(w, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.sp, color = LocalPalette.current.dim)
        SelectionContainer { Text(value, fontSize = 16.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default) }
    }
}

