package net.kravos.scan.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import net.kravos.scan.img.Bitmaps
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import kotlin.math.hypot

/** Manual 4-corner crop with perspective correction, on the original capture. */
@Composable
fun CropScreen(docId: String, pageId: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val doc = app.store.get(docId)
    val page = doc?.pages?.firstOrNull { it.id == pageId }
    if (doc == null || page == null) { LaunchedEffect(Unit) { nav.back() }; return }
    val origFile = app.store.file(doc, page.orig)
    val (iw, ih) = remember(origFile) { Bitmaps.boundsOf(origFile) }
    val full = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    val q = remember { mutableStateListOf<Float>().apply { addAll(page.quad ?: full) } }
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        TopBar("Iseci", onBack = { nav.back() }) {
            TextButton(onClick = { q.clear(); q.addAll(full) }) { Text("Ceo kadar") }
        }
        Text(
            "Povuci uglove do ivica papira. Perspektiva se ispravlja automatski.",
            Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = LocalPalette.current.dim,
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
            val fit = minOf(cw / iw.coerceAtLeast(1), ch / ih.coerceAtLeast(1))
            val dw = iw * fit; val dh = ih * fit
            val left = (cw - dw) / 2; val top = (ch - dh) / 2
            AsyncImage(model = origFile, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            val grab = with(density) { 44.dp.toPx() }
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    var active = -1
                    detectDragGestures(
                        onDragStart = { pos ->
                            active = (0 until 4).minByOrNull { i -> hypot(left + q[2 * i] * dw - pos.x, top + q[2 * i + 1] * dh - pos.y) }
                                ?.takeIf { i -> hypot(left + q[2 * i] * dw - pos.x, top + q[2 * i + 1] * dh - pos.y) < grab * 1.6f } ?: -1
                        },
                        onDragEnd = { active = -1 },
                    ) { change, d ->
                        if (active < 0) return@detectDragGestures
                        change.consume()
                        q[2 * active] = (q[2 * active] + d.x / dw).coerceIn(0f, 1f)
                        q[2 * active + 1] = (q[2 * active + 1] + d.y / dh).coerceIn(0f, 1f)
                    }
                },
            ) {
                val pts = (0 until 4).map { Offset(left + q[2 * it] * dw, top + q[2 * it + 1] * dh) }
                // dim outside the selection
                val shade = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(left, top, left + dw, top + dh))
                    moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
                }
                drawPath(shade, Color(0x88000000))
                val edge = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                drawPath(edge, accent, style = Stroke(width = 3.dp.toPx()))
                pts.forEach { drawCircle(accent, radius = 11.dp.toPx(), center = it); drawCircle(Color.White, radius = 5.dp.toPx(), center = it) }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { nav.back() }, modifier = Modifier.weight(1f)) { Text("Otkaži") }
            Button(onClick = {
                val v = q.toList()
                app.scope.launch { app.store.setQuad(docId, pageId, if (v == full) null else v) }
                nav.back()
            }, modifier = Modifier.weight(1f)) { Text("Primeni") }
        }
        Spacer(Modifier.padding(0.dp))
    }
}
