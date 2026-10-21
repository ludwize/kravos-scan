package net.kravos.scan.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kravos.scan.img.Bitmaps
import net.kravos.scan.ui.LocalApp
import net.kravos.scan.ui.LocalNav
import net.kravos.scan.ui.Route
import net.kravos.scan.ui.components.Pill
import net.kravos.scan.ui.components.TopBar
import net.kravos.scan.ui.theme.LocalPalette
import kotlin.math.max
import kotlin.math.min

private val Inks = listOf("Plava" to Color(0xFF1A3A8F), "Crna" to Color(0xFF111111))

/** Draw a signature once; it is saved (transparent PNG) and can be placed on any page. */
@Composable
fun SignatureScreen(thenDocId: String?) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    var ink by remember { mutableIntStateOf(0) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize()) {
        TopBar("Potpis", onBack = { PendingSign.docId = null; nav.back() }) {
            TextButton(onClick = { strokes.clear(); current.clear() }) { Text("Obriši") }
        }
        Text("Potpiši se prstom na beloj površini.", Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = LocalPalette.current.dim)
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) { Inks.forEachIndexed { i, (n, _) -> Pill(n, ink == i, { ink = i }) } }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                .onSizeChanged { size = it },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕ ________________________", Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp), color = Color(0xFFBBBBBB), fontSize = 18.sp)
            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current.clear(); current.add(it) },
                        onDragEnd = { if (current.size > 1) strokes.add(current.toList()); current.clear() },
                    ) { ch, _ -> ch.consume(); current.add(ch.position) }
                },
            ) {
                val col = Inks[ink].second
                (strokes + listOf(current.toList())).forEach { s ->
                    if (s.size < 2) return@forEach
                    val p = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(p, col, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { PendingSign.docId = null; nav.back() }, modifier = Modifier.weight(1f)) { Text("Otkaži") }
            Button(enabled = strokes.isNotEmpty(), onClick = {
                saveSignature(app.store.signatureFile(), strokes.toList(), Inks[ink].second, size)
                if (thenDocId == null) PendingSign.docId = null
                nav.back()
            }, modifier = Modifier.weight(1f)) { Text("Sačuvaj") }
        }
    }
}

/** Renders strokes at 2x into a tightly cropped transparent PNG. */
private fun saveSignature(file: java.io.File, strokes: List<List<Offset>>, color: Color, area: IntSize) {
    val all = strokes.flatten()
    if (all.isEmpty()) return
    val pad = 16f
    val x0 = max(0f, all.minOf { it.x } - pad); val y0 = max(0f, all.minOf { it.y } - pad)
    val x1 = min(area.width.toFloat(), all.maxOf { it.x } + pad); val y1 = min(area.height.toFloat(), all.maxOf { it.y } + pad)
    val k = 2f
    val w = ((x1 - x0) * k).toInt().coerceAtLeast(8); val h = ((y1 - y0) * k).toInt().coerceAtLeast(8)
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bm)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb(); style = android.graphics.Paint.Style.STROKE
        strokeWidth = 9f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    for (s in strokes) {
        if (s.size < 2) continue
        val p = android.graphics.Path()
        p.moveTo((s[0].x - x0) * k, (s[0].y - y0) * k)
        s.drop(1).forEach { p.lineTo((it.x - x0) * k, (it.y - y0) * k) }
        c.drawPath(p, paint)
    }
    Bitmaps.savePng(bm, file)
    bm.recycle()
}
