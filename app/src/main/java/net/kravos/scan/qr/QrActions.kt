package net.kravos.scan.qr

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings

/** Android intents for each kind of scanned content. Every call returns false if no app could handle it. */
object QrActions {
    private fun start(ctx: Context, i: Intent): Boolean =
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    fun open(ctx: Context, url: String) = start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

    fun copy(ctx: Context, label: String, text: String) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun share(ctx: Context, text: String) =
        start(ctx, Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Podeli"))

    fun webSearch(ctx: Context, q: String) =
        start(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
            open(ctx, "https://www.google.com/search?q=" + Uri.encode(q))

    fun dial(ctx: Context, n: String) = start(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))

    fun sms(ctx: Context, n: String, body: String?) =
        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n))).apply { body?.let { putExtra("sms_body", it) } })

    fun email(ctx: Context, to: String, subject: String?, body: String?) =
        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .apply { subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }; body?.let { putExtra(Intent.EXTRA_TEXT, it) } })

    fun map(ctx: Context, g: QrContent.Geo) =
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${g.lat},${g.lon}?q=${g.lat},${g.lon}" + (g.query?.let { "(" + Uri.encode(it) + ")" } ?: ""))))

    fun addContact(ctx: Context, c: QrContent.Contact) = start(ctx, Intent(ContactsContract.Intents.Insert.ACTION).apply {
        type = ContactsContract.RawContacts.CONTENT_TYPE
        c.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        c.org?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        c.phones.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        c.phones.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, it) }
        c.emails.getOrNull(0)?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        c.emails.getOrNull(1)?.let { putExtra(ContactsContract.Intents.Insert.SECONDARY_EMAIL, it) }
        c.address?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
    })

    fun addEvent(ctx: Context, e: QrContent.Event) = start(ctx, Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI).apply {
        e.title?.let { putExtra(CalendarContract.Events.TITLE, it) }
        e.startMs?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) }
        e.endMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        e.location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        e.description?.let { putExtra(CalendarContract.Events.DESCRIPTION, it) }
    })

    /**
     * Android 11+: the system "Add network" dialog (no Wi-Fi permission needed).
     * Older phones / WEP: copy the password and open Wi-Fi settings.
     */
    fun joinWifi(ctx: Context, w: QrContent.Wifi): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && w.security != "WEP") {
            val b = WifiNetworkSuggestion.Builder().setSsid(w.ssid).setIsHiddenSsid(w.hidden)
            w.password?.let { p -> if (w.security == "WPA3") b.setWpa3Passphrase(p) else b.setWpa2Passphrase(p) }
            val list = arrayListOf(b.build())
            if (start(ctx, Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, list))) return true
        }
        w.password?.let { copy(ctx, "Wi-Fi lozinka", it) }
        return start(ctx, Intent(Settings.ACTION_WIFI_SETTINGS))
    }
}
