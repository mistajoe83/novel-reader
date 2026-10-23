package com.joe.novelreader.core

import com.joe.novelreader.core.net.AppDns
import com.joe.novelreader.core.util.HtmlText
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineTest {
    @Test
    fun normalizesDnsServers() {
        assertEquals("https://dns.adguard.com/dns-query", AppDns.normalizeServer("dns.adguard.com"))
        assertEquals("https://dns.adguard.com/dns-query", AppDns.normalizeServer("https://dns.adguard.com"))
        assertEquals("https://dns.google/resolve", AppDns.normalizeServer("dns.google/resolve"))
        assertEquals("https://94.140.14.14/dns-query", AppDns.normalizeServer("94.140.14.14"))
        assertNull(AppDns.normalizeServer("   "))
    }

    @Test
    fun splitsParagraphs() {
        val doc = Jsoup.parse(
            """<div id="c"><p>One  two</p>Loose line<br>Second<br/><script>x()</script><p>Read latest chapters at site.com</p><div><p>Nested</p></div></div>""",
        )
        val p = HtmlText.paragraphs(doc.getElementById("c")!!, HtmlText.COMMON_JUNK)
        assertEquals(listOf("One two", "Loose line", "Second", "Nested"), p)
    }

    @Test
    fun dropsWatermarks() {
        val p = listOf("Real text here.", "𝐟𝐫𝐞𝐞𝐰𝐞𝐛𝐧𝐨𝐯𝐞𝐥.𝐜𝐨𝐦", "More text.")
        assertEquals(listOf("Real text here.", "More text."), HtmlText.dropWatermarks(p, listOf("freewebnovel")))
    }
}
