package net.kravos.scan.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.kravos.scan.App
import net.kravos.scan.data.DocKind
import net.kravos.scan.ui.screens.CropScreen
import net.kravos.scan.ui.screens.DocumentScreen
import net.kravos.scan.ui.screens.LibraryScreen
import net.kravos.scan.ui.screens.SettingsScreen
import net.kravos.scan.ui.screens.SignatureScreen
import net.kravos.scan.ui.theme.ScanTheme
import net.kravos.scan.update.Release
import net.kravos.scan.update.Updater

class MainActivity : ComponentActivity() {
    private val nav = Navigator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val theme by app.prefs.theme.collectAsState()
            ScanTheme(theme) {
                CompositionLocalProvider(LocalNav provides nav, LocalApp provides app) { Root(app) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Share to Kravos Sken" from the gallery or other apps, plus the CI smoke-test hook. */
    private fun handleIntent(i: Intent?) {
        i ?: return
        val app = application as App
        if (i.getBooleanExtra("kravos_smoke", false)) {
            app.scope.launch { SmokeSamples.create(app) }
            return
        }
        val uris: List<Uri> = when (i.action) {
            Intent.ACTION_SEND -> listOfNotNull(streamExtra(i))
            Intent.ACTION_SEND_MULTIPLE -> streamList(i)
            else -> emptyList()
        }
        if (uris.isEmpty() || i.type?.startsWith("image/") != true) return
        app.scope.launch {
            val d = app.newDocument(DocKind.DOC, uris)
            if (d != null) runOnUiThread { nav.home(); nav.go(Route.Document(d.id)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun streamList(i: Intent): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
}

@Composable
private fun Root(app: App) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var bannerHidden by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (app.prefs.autoUpdateCheck) release = Updater.latest() }

    // ---- scanner plumbing
    var pending by remember { mutableStateOf<ScanJob?>(null) }
    var importInto by remember { mutableStateOf<String?>(null) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val job = pending; pending = null
        val uris = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pages?.map { it.imageUri }.orEmpty()
        if (job == null || uris.isEmpty()) return@rememberLauncherForActivityResult
        app.scope.launch {
            val docId: String? = when (job) {
                ScanJob.NewDoc -> app.newDocument(DocKind.DOC, uris)?.id
                is ScanJob.AddPages -> { app.addPages(job.docId, uris); null }
                is ScanJob.Photo -> app.photo(uris, job.intoDoc)?.id?.takeIf { job.intoDoc == null }
                is ScanJob.Id -> app.idCard(uris, job.kind)?.id
            }
            if (docId != null) activity.runOnUiThread { nav.go(Route.Document(docId)) }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        val into = importInto; importInto = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        app.scope.launch {
            if (into != null) app.addPages(into, uris)
            else app.newDocument(DocKind.DOC, uris)?.let { d -> activity.runOnUiThread { nav.go(Route.Document(d.id)) } }
        }
    }
    val actions = remember {
        object : ScanActions {
            override fun scan(job: ScanJob) {
                val limit = when (job) {
                    is ScanJob.Photo -> 4
                    is ScanJob.Id -> job.kind.sides
                    else -> 100
                }
                val opts = GmsDocumentScannerOptions.Builder()
                    .setGalleryImportAllowed(true)
                    .setPageLimit(limit)
                    .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                    .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                    .build()
                scope.launch {
                    try {
                        val sender = GmsDocumentScanning.getClient(opts).getStartScanIntent(activity).await()
                        pending = job
                        scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } catch (e: Exception) {
                        Log.w(App.TAG, "scanner unavailable", e)
                        app.toast("Skener nije dostupan (${e.message}). Možeš da uvezeš slike iz galerije.")
                    }
                }
            }

            override fun importImages(intoDoc: String?) {
                importInto = intoDoc
                pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.back() }

    val busy by app.busy.collectAsState()
    val msg by app.message.collectAsState()
    LaunchedEffect(msg) { if (msg != null) { delay(4000); app.toast(null) } }

    CompositionLocalProvider(LocalScan provides actions) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val r = release
                val showBanner = r != null && Updater.isNewer(r) && !bannerHidden && r.build != app.prefs.skippedBuild
                if (showBanner && r != null) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).statusBarsPadding()
                            .clickable { bannerHidden = true; nav.go(Route.Settings) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("Nova verzija 1.0.${r.build} je spremna — dodirni za instalaciju", Modifier.weight(1f), color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text("Kasnije", Modifier.clickable { bannerHidden = true; app.prefs.skippedBuild = r.build }, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(targetState = nav.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { dest ->
                        when (dest) {
                            Route.Library -> LibraryScreen(showBanner)
                            is Route.Document -> DocumentScreen(dest.id)
                            is Route.Crop -> CropScreen(dest.docId, dest.pageId)
                            is Route.Signature -> SignatureScreen(dest.thenDocId)
                            Route.Settings -> SettingsScreen(release) { scope.launch { release = Updater.latest() } }
                        }
                    }
                }
            }
            if (busy != null) {
                Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(36.dp))
                            Spacer(Modifier.height(14.dp))
                            Text(busy ?: "", fontSize = 15.sp)
                        }
                    }
                }
            }
            msg?.let { m ->
                Box(Modifier.fillMaxSize().padding(16.dp).padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
                    Snackbar { Text(m) }
                }
            }
        }
    }
    }
    @Suppress("unused") val keep = Arrangement.Center
}
