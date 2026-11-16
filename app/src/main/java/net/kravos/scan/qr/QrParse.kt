package net.kravos.scan.qr

import java.net.URI

/** What a scanned code contains, parsed from its raw text (platform-free, unit-tested). */
sealed class QrContent {
    data class Link(val url: String, val check: LinkCheck) : QrContent()
    data class Wifi(val ssid: String, val password: String?, val security: String, val hidden: Boolean) : QrContent()
    /** NBS IPS QR (Serbian instant payment, printed on bills). */
    data class Ips(
        val kind: String, val account: String, val payee: String, val amount: String?, val currency: String?,
        val payer: String?, val code: String?, val purpose: String?, val reference: String?,
    ) : QrContent()
    data class Phone(val number: String) : QrContent()
    data class Sms(val number: String, val body: String?) : QrContent()
    data class Email(val to: String, val subject: String?, val body: String?) : QrContent()
    data class Geo(val lat: Double, val lon: Double, val query: String?) : QrContent()
    data class Contact(val name: String?, val org: String?, val phones: List<String>, val emails: List<String>, val address: String?, val url: String?) : QrContent()
    data class Event(val title: String?, val startMs: Long?, val endMs: Long?, val location: String?, val description: String?) : QrContent()
    /** Product barcode (EAN/UPC…) or any plain text. */
    data class Text(val text: String, val product: Boolean) : QrContent()

    val label: String get() = when (this) {
        is Link -> "Link"
        is Wifi -> "Wi-Fi mreža"
        is Ips -> "IPS uplata"
        is Phone -> "Telefon"
        is Sms -> "SMS"
        is Email -> "Mejl"
        is Geo -> "Lokacija"
        is Contact -> "Kontakt"
        is Event -> "Događaj"
        is Text -> if (product) "Bar kod proizvoda" else "Tekst"
    }

    /** One-line summary for the history list. */
    val title: String get() = when (this) {
        is Link -> check.host.ifEmpty { url }
        is Wifi -> ssid
        is Ips -> payee.lineSequence().first() + (amount?.let { " · $it ${currency ?: ""}".trimEnd() } ?: "")
        is Phone -> number
        is Sms -> number
        is Email -> to
        is Geo -> query ?: "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon)
        is Contact -> name ?: org ?: phones.firstOrNull() ?: emails.firstOrNull() ?: "Kontakt"
        is Event -> title ?: "Događaj"
        is Text -> text.lineSequence().first().take(80)
    }
}

/** Phishing-style red flags of a link, shown before it is opened. */
data class LinkCheck(val host: String, val domain: String, val secure: Boolean, val warnings: List<String>) {
    val safe: Boolean get() = warnings.isEmpty()
}

object QrParse {

    fun parse(raw: String, product: Boolean = false): QrContent {
        val s = raw.trim()
        if (product) return QrContent.Text(s, true)
        val up = s.uppercase()
        return runCatching {
            when {
                up.startsWith("K:P") && s.contains("|R:") -> ips(s)
                up.startsWith("WIFI:") -> wifi(s)
                up.startsWith("HTTP://") || up.startsWith("HTTPS://") -> link(s)
                up.startsWith("TEL:") -> QrContent.Phone(s.substring(4).trim())
                up.startsWith("SMSTO:") || up.startsWith("SMS:") -> sms(s)
                up.startsWith("MAILTO:") -> mailto(s)
                up.startsWith("MATMSG:") -> matmsg(s)
                up.startsWith("GEO:") -> geo(s)
                up.startsWith("MECARD:") -> mecard(s)
                up.startsWith("BEGIN:VCARD") -> vcard(s)
                up.startsWith("BEGIN:VEVENT") || up.startsWith("BEGIN:VCALENDAR") -> vevent(s)
                // "www.example.rs" / "example.com/path" without a scheme
                Regex("""^(www\.)?[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}(/\S*)?$""", RegexOption.IGNORE_CASE).matches(s) && !s.contains(' ') -> link("https://$s")
                else -> null
            }
        }.getOrNull() ?: QrContent.Text(s, false)
    }

    // ------------------------------------------------------------------ links

    private val shorteners = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at",
        "tiny.cc", "rb.gy", "qrco.de", "s.id", "t.ly", "v.gd", "lnkd.in", "bl.ink", "short.io", "u.to", "clck.ru", "qr.codes",
    )
    private val secondLevel = setOf("co", "com", "org", "net", "edu", "gov", "ac", "in", "upr", "mil")

    fun link(url: String): QrContent {
        // percent-encode characters java.net.URI rejects but real-world QR links sometimes contain
        val safe = buildString { for (c in url) if (c in " \"<>\\^`{|}") append("%%%02X".format(c.code)) else append(c) }
        val u = URI(safe)
        val scheme = u.scheme?.lowercase() ?: return QrContent.Text(url, false)
        if (scheme != "http" && scheme != "https") return QrContent.Text(url, false)
        val host = (u.host ?: u.rawAuthority?.substringAfterLast('@')?.substringBefore(':') ?: "").lowercase().trimEnd('.')
        val w = mutableListOf<String>()
        if (scheme == "http") w += "Veza nije šifrovana (http, ne https)"
        if (!u.rawUserInfo.isNullOrEmpty()) w += "Link sadrži „@“ — tekst pre njega je mamac, pravi sajt je $host"
        val isIp = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host) || host.startsWith("[")
        if (isIp) w += "Adresa je IP broj, ne ime sajta"
        if (host.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 })
            w += "Ime sajta sadrži nelatinična slova — može da imitira poznat sajt"
        val domain = registrable(host)
        if (domain in shorteners) w += "Skraćen link — pravo odredište se ne vidi pre otvaranja"
        if (u.port != -1 && u.port != 80 && u.port != 443) w += "Neobičan port (${u.port})"
        if (host.count { it == '.' } >= 4 && !isIp) w += "Neobično dugačko ime sajta"
        if (url.length > 400) w += "Neobično dugačak link"
        return QrContent.Link(url, LinkCheck(host, domain, scheme == "https", w))
    }

    /** "login.bank.co.rs" → "bank.co.rs", "a.b.example.com" → "example.com". */
    fun registrable(host: String): String {
        val p = host.split('.').filter { it.isNotEmpty() }
        if (p.size <= 2 || Regex("""^\d+$""").matches(p.last())) return host
        return if (p[p.size - 2] in secondLevel && p.last().length == 2) p.takeLast(3).joinToString(".") else p.takeLast(2).joinToString(".")
    }

    // ------------------------------------------------------------------ Serbian IPS (NBS)

    /** Splits "K:PR|V:01|R:...|N:..." into tags; values may contain ':' and newlines. */
    private fun ips(s: String): QrContent {
        val m = s.split('|').mapNotNull { part ->
            val i = part.indexOf(':'); if (i <= 0) null else part.substring(0, i).trim().uppercase() to part.substring(i + 1).trim()
        }.toMap()
        val acc = m["R"]?.filter { it.isDigit() } ?: error("no account")
        val amount = m["I"]?.let { Regex("""^([A-Z]{3})?\s*([\d.,]+)$""").find(it.trim()) }
        return QrContent.Ips(
            kind = m["K"] ?: "PR",
            account = formatAccount(acc),
            payee = m["N"]?.replace("\r\n", "\n").orEmpty(),
            amount = amount?.groupValues?.get(2),
            currency = amount?.groupValues?.get(1)?.ifEmpty { null },
            payer = m["P"]?.replace("\r\n", "\n"),
            code = m["SF"],
            purpose = m["S"],
            reference = m["RO"]?.let { ro -> if (ro.length > 2 && ro.take(2).all(Char::isDigit)) "(${ro.take(2)}) ${ro.drop(2)}" else ro },
        )
    }

    /** 18-digit account → "845-0000000404849-87". */
    fun formatAccount(d: String): String = if (d.length == 18) "${d.take(3)}-${d.substring(3, 16)}-${d.takeLast(2)}" else d

    // ------------------------------------------------------------------ Wi-Fi, contacts, messages

    /** Splits "A:x;B:y\;z;;" respecting backslash escapes. */
    private fun fields(body: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val cur = StringBuilder(); var esc = false
        fun flush() {
            val f = cur.toString(); cur.clear()
            val i = indexOfUnescapedColon(f)
            if (i > 0) out += f.substring(0, i).uppercase() to unescape(f.substring(i + 1))
        }
        for (c in body) {
            when {
                esc -> { cur.append('\\').append(c); esc = false }
                c == '\\' -> esc = true
                c == ';' -> flush()
                else -> cur.append(c)
            }
        }
        flush()
        return out
    }

    private fun indexOfUnescapedColon(s: String): Int {
        var i = 0
        while (i < s.length) { if (s[i] == '\\') i += 2 else if (s[i] == ':') return i else i++ }
        return -1
    }

    private fun unescape(s: String) = s.replace(Regex("""\\(.)"""), "$1")

    private fun wifi(s: String): QrContent {
        val f = fields(s.substring(5)).toMap()
        val t = (f["T"] ?: "").uppercase()
        val sec = when {
            t.contains("WPA3") || t == "SAE" -> "WPA3"
            t.startsWith("WPA") -> "WPA/WPA2"
            t == "WEP" -> "WEP"
            else -> if (f["P"].isNullOrEmpty()) "Otvorena" else "WPA/WPA2"
        }
        return QrContent.Wifi(f["S"] ?: error("no ssid"), f["P"]?.ifEmpty { null }, sec, f["H"].equals("true", true))
    }

    private fun sms(s: String): QrContent {
        val body = s.substringAfter(':')
        val num = body.substringBefore(':').substringBefore('?')
        val msg = when {
            body.contains("?body=") -> java.net.URLDecoder.decode(body.substringAfter("?body="), "UTF-8")
            body.contains(':') -> body.substringAfter(':')
            else -> null
        }
        return QrContent.Sms(num.trim(), msg?.ifEmpty { null })
    }

    private fun mailto(s: String): QrContent {
        val rest = s.substring(7)
        val to = rest.substringBefore('?')
        val q = if (rest.contains('?')) rest.substringAfter('?').split('&').associate {
            it.substringBefore('=').lowercase() to java.net.URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        } else emptyMap()
        return QrContent.Email(java.net.URLDecoder.decode(to, "UTF-8"), q["subject"], q["body"])
    }

    private fun matmsg(s: String): QrContent {
        val f = fields(s.substring(7)).toMap()
        return QrContent.Email(f["TO"] ?: error("no to"), f["SUB"], f["BODY"])
    }

    private fun geo(s: String): QrContent {
        val body = s.substring(4)
        val coords = body.substringBefore('?').split(',')
        val q = if (body.contains("q=")) java.net.URLDecoder.decode(body.substringAfter("q=").substringBefore('&'), "UTF-8") else null
        return QrContent.Geo(coords[0].trim().toDouble(), coords[1].trim().substringBefore(';').toDouble(), q)
    }

    private fun mecard(s: String): QrContent {
        val f = fields(s.substring(7))
        fun all(k: String) = f.filter { it.first == k }.map { it.second }.filter { it.isNotBlank() }
        val n = f.firstOrNull { it.first == "N" }?.second?.let { nm -> if (nm.contains(',')) nm.split(',').reversed().joinToString(" ") { it.trim() } else nm }
        return QrContent.Contact(n, f.firstOrNull { it.first == "ORG" }?.second, all("TEL"), all("EMAIL"), f.firstOrNull { it.first == "ADR" }?.second, f.firstOrNull { it.first == "URL" }?.second)
    }

    /** Reads "KEY;params:value" lines, unfolding continuation lines. */
    private fun lines(s: String): List<Pair<String, String>> =
        s.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").lines().mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).uppercase() to l.substring(i + 1).replace("\\n", "\n").replace("\\,", ",").replace("\\;", ";")
        }

    private fun vcard(s: String): QrContent {
        val l = lines(s)
        fun key(k: String) = l.filter { it.first == k || it.first.startsWith("$k;") }.map { it.second.trim() }.filter { it.isNotEmpty() }
        val name = key("FN").firstOrNull() ?: key("N").firstOrNull()?.split(';')?.let { p -> listOfNotNull(p.getOrNull(1), p.getOrNull(0)).joinToString(" ").trim() }?.ifEmpty { null }
        val adr = key("ADR").firstOrNull()?.split(';')?.filter { it.isNotBlank() }?.joinToString(", ")
        return QrContent.Contact(name, key("ORG").firstOrNull()?.replace(';', ' ')?.trim(), key("TEL"), key("EMAIL"), adr, key("URL").firstOrNull())
    }

    private fun vevent(s: String): QrContent {
        val l = lines(s)
        fun key(k: String) = l.firstOrNull { it.first == k || it.first.startsWith("$k;") }?.second?.trim()
        return QrContent.Event(key("SUMMARY"), key("DTSTART")?.let(::icsTime), key("DTEND")?.let(::icsTime), key("LOCATION"), key("DESCRIPTION"))
    }

    /** "20261121T190000Z" / "20261121T190000" (local) / "20261121" → epoch ms. */
    fun icsTime(v: String): Long? = runCatching {
        val utc = v.endsWith("Z")
        val t = v.trimEnd('Z')
        val fmt = if (t.length == 8) "yyyyMMdd" else "yyyyMMdd'T'HHmmss"
        val df = java.text.SimpleDateFormat(fmt, java.util.Locale.ROOT)
        if (utc) df.timeZone = java.util.TimeZone.getTimeZone("UTC")
        df.parse(t)!!.time
    }.getOrNull()
}
