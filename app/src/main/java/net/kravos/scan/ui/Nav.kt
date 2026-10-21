package net.kravos.scan.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import net.kravos.scan.App
import net.kravos.scan.img.Ops

sealed class Route {
    data object Library : Route()
    data class Document(val id: String) : Route()
    data class Crop(val docId: String, val pageId: String) : Route()
    /** [then] = document to return to and place the signature on. */
    data class Signature(val thenDocId: String? = null) : Route()
    data object Settings : Route()
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Library)
    val top: Route get() = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun replace(r: Route) { if (stack.size > 1) stack.removeAt(stack.lastIndex); stack.add(r) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

/** What the next ML Kit scanner session is for. */
sealed class ScanJob {
    data object NewDoc : ScanJob()
    data class AddPages(val docId: String) : ScanJob()
    data class Photo(val intoDoc: String? = null) : ScanJob()
    data class Id(val kind: Ops.IdKind) : ScanJob()
}

interface ScanActions {
    fun scan(job: ScanJob)
    fun importImages(intoDoc: String? = null)
}

val LocalNav = staticCompositionLocalOf { Navigator() }
val LocalApp = staticCompositionLocalOf<App> { error("no app") }
val LocalScan = staticCompositionLocalOf<ScanActions> { error("no scanner") }
