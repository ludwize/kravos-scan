package net.kravos.scan.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.kravos.scan.img.Filter
import net.kravos.scan.pdf.PageSize

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class OcrLang(val code: String, val label: String) {
    SRP("srp", "Srpski (ćirilica)"),
    SRP_LATN("srp_latn", "Srpski (latinica)"),
    ENG("eng", "Engleski"),
}

enum class Quality(val label: String, val jpeg: Int) {
    STANDARD("Standardni", 85),
    HIGH("Visok", 92),
    MAX("Maksimalni", 98),
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(runCatching { ThemeMode.valueOf(sp.getString("theme", "DARK")!!) }.getOrDefault(ThemeMode.DARK))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(m: ThemeMode) { sp.edit().putString("theme", m.name).apply(); _theme.value = m }

    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdate", true)
        set(v) = sp.edit().putBoolean("autoUpdate", v).apply()

    var skippedBuild: Int
        get() = sp.getInt("skippedBuild", 0)
        set(v) = sp.edit().putInt("skippedBuild", v).apply()

    var docFilter: Filter
        get() = runCatching { Filter.valueOf(sp.getString("docFilter", Filter.MAGIC.name)!!) }.getOrDefault(Filter.MAGIC)
        set(v) = sp.edit().putString("docFilter", v.name).apply()

    fun defaultFilter(kind: DocKind) = when (kind) {
        DocKind.DOC -> docFilter
        DocKind.PHOTO -> Filter.PHOTO
        DocKind.ID -> Filter.AUTO
    }

    var pageSize: PageSize
        get() = runCatching { PageSize.valueOf(sp.getString("pageSize", PageSize.A4.name)!!) }.getOrDefault(PageSize.A4)
        set(v) = sp.edit().putString("pageSize", v.name).apply()

    var quality: Quality
        get() = runCatching { Quality.valueOf(sp.getString("quality", Quality.HIGH.name)!!) }.getOrDefault(Quality.HIGH)
        set(v) = sp.edit().putString("quality", v.name).apply()
    val jpegQuality: Int get() = quality.jpeg

    var autoOcr: Boolean
        get() = sp.getBoolean("autoOcr", true)
        set(v) = sp.edit().putBoolean("autoOcr", v).apply()

    var ocrLangs: Set<OcrLang>
        get() = sp.getStringSet("ocrLangs", null)?.mapNotNull { n -> OcrLang.entries.firstOrNull { it.name == n } }?.toSet()
            ?.ifEmpty { null } ?: OcrLang.entries.toSet()
        set(v) = sp.edit().putStringSet("ocrLangs", v.map { it.name }.toSet()).apply()

    var photoTipSeen: Boolean
        get() = sp.getBoolean("photoTip", false)
        set(v) = sp.edit().putBoolean("photoTip", v).apply()

    /** Last ID document type chosen, so the next scan starts there. */
    var lastIdKind: String
        get() = sp.getString("idKind", "ID_CARD")!!
        set(v) = sp.edit().putString("idKind", v).apply()
}
