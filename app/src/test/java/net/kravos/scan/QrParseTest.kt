package net.kravos.scan

import net.kravos.scan.qr.QrContent
import net.kravos.scan.qr.QrParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrParseTest {
    private inline fun <reified T> p(s: String): T {
        val c = QrParse.parse(s)
        assertTrue("expected ${T::class.simpleName}, got $c", c is T)
        return c as T
    }

    @Test fun safeLink() {
        val l = p<QrContent.Link>("https://www.rts.rs/vesti/123?x=1")
        assertEquals("www.rts.rs", l.check.host); assertEquals("rts.rs", l.check.domain)
        assertTrue(l.check.safe)
    }

    @Test fun schemeLessLink() = assertEquals("https://www.kravos.net", p<QrContent.Link>("www.kravos.net").url)

    @Test fun phishingSigns() {
        val at = p<QrContent.Link>("https://www.bancaintesa.rs@evil.example.com/login")
        assertEquals("evil.example.com", at.check.host); assertEquals("example.com", at.check.domain)
        assertTrue(at.check.warnings.any { "@" in it })
        assertTrue(p<QrContent.Link>("http://192.168.1.10:8080/x").check.warnings.size >= 3) // http + IP + port
        assertTrue(p<QrContent.Link>("https://bit.ly/3abc").check.warnings.any { "Skraćen" in it })
        assertFalse(p<QrContent.Link>("https://xn--pypal-4ve.com/").check.safe)
        assertEquals("bank.co.rs", QrParse.registrable("login.bank.co.rs"))
        // odd characters don't crash, they still parse as a link
        assertEquals("example.com", p<QrContent.Link>("https://example.com/a b|c").check.domain)
        // non-web schemes are never treated as openable links
        p<QrContent.Text>("javascript:alert(1)"); p<QrContent.Text>("intent://x#Intent;end")
    }

    @Test fun ips() {
        val i = p<QrContent.Ips>("K:PR|V:01|C:1|R:845000000040484987|N:JKP INFOSTAN TEHNOLOGIJE\r\nBeograd|I:RSD3596,13|SF:189|S:UPLATA PO RAČUNU ZA EDV|RO:97163220000111111111000")
        assertEquals("845-0000000404849-87", i.account)
        assertEquals("3596,13", i.amount); assertEquals("RSD", i.currency)
        assertEquals("JKP INFOSTAN TEHNOLOGIJE\nBeograd", i.payee)
        assertEquals("(97) 163220000111111111000", i.reference)
        assertEquals("189", i.code)
    }

    @Test fun wifi() {
        val w = p<QrContent.Wifi>("WIFI:T:WPA;S:Kravos\\;Net;P:ta\\:jna;H:true;;")
        assertEquals("Kravos;Net", w.ssid); assertEquals("ta:jna", w.password); assertTrue(w.hidden)
        assertEquals("Otvorena", p<QrContent.Wifi>("WIFI:S:Kafić;T:nopass;P:;;").security)
    }

    @Test fun contactsAndMessages() {
        val m = p<QrContent.Contact>("MECARD:N:Petrović,Petar;TEL:+38160123456;EMAIL:p@x.rs;;")
        assertEquals("Petar Petrović", m.name); assertEquals(listOf("+38160123456"), m.phones)
        val v = p<QrContent.Contact>("BEGIN:VCARD\nVERSION:3.0\nN:Jović;Ana\nFN:Ana Jović\nORG:Kravos\nTEL;TYPE=CELL:+381641112233\nEMAIL:ana@kravos.net\nEND:VCARD")
        assertEquals("Ana Jović", v.name); assertEquals("Kravos", v.org); assertEquals("ana@kravos.net", v.emails.single())
        assertEquals("+381111", p<QrContent.Phone>("tel:+381111").number)
        val s = p<QrContent.Sms>("SMSTO:1234:START"); assertEquals("1234", s.number); assertEquals("START", s.body)
        val e = p<QrContent.Email>("mailto:a@b.rs?subject=Zdravo%20Gorane&body=Hi"); assertEquals("Zdravo Gorane", e.subject)
        assertEquals("b@c.rs", p<QrContent.Email>("MATMSG:TO:b@c.rs;SUB:Hi;BODY:Yo;;").to)
        val g = p<QrContent.Geo>("geo:44.8125,20.4612?q=Knez%20Mihailova"); assertEquals(20.4612, g.lon, 1e-6); assertEquals("Knez Mihailova", g.query)
        val ev = p<QrContent.Event>("BEGIN:VEVENT\nSUMMARY:Proba benda\nDTSTART:20261121T180000Z\nDTEND:20261121T200000Z\nLOCATION:Dorćol\nEND:VEVENT")
        assertEquals("Proba benda", ev.title); assertEquals(7_200_000L, ev.endMs!! - ev.startMs!!)
    }

    @Test fun textAndProducts() {
        assertEquals("Zdravo svete", p<QrContent.Text>("Zdravo svete").text)
        assertTrue((QrParse.parse("8600043003401", product = true) as QrContent.Text).product)
    }
}
