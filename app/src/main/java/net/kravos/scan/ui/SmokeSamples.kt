package net.kravos.scan.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import net.kravos.scan.App
import net.kravos.scan.data.DocKind
import net.kravos.scan.img.Ops
import java.io.File
import java.io.FileOutputStream

/**
 * Synthetic "camera shots" used only by the CI emulator smoke test (launched with the
 * kravos_smoke extra): a shadowed letter page, three glare shots of a print, and an ID card.
 * Exercises the same pipeline as real scans, without needing a camera.
 */
object SmokeSamples {
    private fun save(app: App, bm: Bitmap, name: String): Uri {
        val f = File(app.cacheDir, name)
        FileOutputStream(f).use { bm.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bm.recycle()
        return Uri.fromFile(f)
    }

    private fun letter(): Bitmap {
        val w = 1700; val h = 2300
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        // warm paper under uneven light: bright top-left, shadow toward the bottom-right
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(236, 230, 214), Color.rgb(150, 140, 120), Shader.TileMode.CLAMP)
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 52); textSize = 54f; typeface = Typeface.SERIF }
        val b = Paint(p).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textSize = 72f }
        var y = 200f
        c.drawText("РЕПУБЛИКА СРБИЈА", 140f, y, b); y += 110
        c.drawText("Уговор о закупу стана", 140f, y, p); y += 90
        val lines = listOf(
            "Закуподавац издаје стан на Врачару, у улици",
            "Светог Саве 12, површине 54 квадратна метра.",
            "Zakupac plaća mesečnu zakupninu do petog u mesecu.",
            "Ugovor važi godinu dana od dana potpisivanja.",
            "The tenant must keep the apartment in good order.",
            "Beograd, 21. oktobar 2026.",
        )
        for (l in lines) { c.drawText(l, 140f, y, p); y += 80 }
        y += 60
        val line = Paint().apply { color = Color.rgb(60, 60, 70); strokeWidth = 3f }
        for (r in 0..4) c.drawLine(140f, y + r * 90, w - 140f, y + r * 90, line)
        for (x in listOf(140f, 800f, w - 140f)) c.drawLine(x, y, x, y + 360, line)
        val t = Paint(p).apply { textSize = 46f }
        listOf("Stavka" to "Iznos", "Zakupnina" to "450 EUR", "Depozit" to "450 EUR", "Ukupno" to "900 EUR").forEachIndexed { i, (a, bb) ->
            c.drawText(a, 170f, y + 62 + i * 90, t); c.drawText(bb, 830f, y + 62 + i * 90, t)
        }
        y += 520
        c.drawText("Закуподавац ____________      Закупац ____________", 140f, y, p)
        return bm
    }

    private fun print(glareX: Float, glareY: Float, dx: Int): Bitmap {
        val w = 1500; val h = 1050
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.translate(dx.toFloat(), 0f)
        // faded, yellowed landscape print
        c.drawRect(-40f, 0f, w + 40f, h * 0.55f, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.rgb(150, 175, 185), Color.rgb(205, 200, 170), Shader.TileMode.CLAMP) })
        c.drawRect(-40f, h * 0.55f, w + 40f, h.toFloat(), Paint().apply { shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.rgb(140, 150, 90), Color.rgb(110, 105, 70), Shader.TileMode.CLAMP) })
        c.drawCircle(1150f, 230f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 210, 140) })
        val tree = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 85, 55) }
        for (i in 0 until 6) c.drawCircle(200f + i * 210f, h * 0.55f - 40f, 70f + (i % 3) * 20, tree)
        c.drawRect(480f, 420f, 760f, 640f, Paint().apply { color = Color.rgb(170, 120, 95) })
        c.drawRect(0f, 0f, 0f, 0f, Paint())
        c.translate(-dx.toFloat(), 0f)
        // specular glare from a lamp, different spot on every shot
        c.drawCircle(glareX, glareY, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(glareX, glareY, 260f, intArrayOf(Color.argb(250, 255, 255, 255), Color.argb(170, 255, 255, 255), Color.argb(0, 255, 255, 255)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        return bm
    }

    private fun card(back: Boolean): Bitmap {
        val w = 1280; val h = 810
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(200, 215, 235), Color.rgb(235, 220, 210), Shader.TileMode.CLAMP) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 40, 70); textSize = 48f }
        if (!back) {
            c.drawText("ЛИЧНА КАРТА · OGLEDNI PRIMERAK", 60f, 90f, p)
            c.drawRect(60f, 160f, 420f, 640f, Paint().apply { color = Color.rgb(160, 170, 185) })
            listOf("Презиме: ПРИМЕР", "Име: ТЕСТ", "Датум рођења: 01.01.1980.").forEachIndexed { i, s -> c.drawText(s, 470f, 240f + i * 90, p) }
        } else {
            c.drawText("Пребивалиште: Београд", 60f, 160f, p)
            for (i in 0 until 3) c.drawText("IDSRB<<<<<<<<<<<<<<<<<<<<<<<<<", 60f, 560f + i * 70, Paint(p).apply { typeface = Typeface.MONOSPACE })
        }
        return bm
    }

    suspend fun create(app: App) {
        if (app.store.docs.value.isNotEmpty()) return
        app.newDocument(DocKind.DOC, listOf(save(app, letter(), "smoke_letter.jpg")))
        val shots = listOf(Triple(380f, 300f, 0), Triple(1050f, 650f, 6), Triple(700f, 820f, -5))
            .mapIndexed { i, (x, y, dx) -> save(app, print(x, y, dx), "smoke_print$i.jpg") }
        app.photo(shots, null)
        app.idCard(listOf(save(app, card(false), "smoke_front.jpg"), save(app, card(true), "smoke_back.jpg")), Ops.IdKind.ID_CARD)
    }
}
