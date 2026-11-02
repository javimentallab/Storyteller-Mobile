package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Cue
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.data.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun times() {
        assertEquals("0:00", Format.time(0))
        assertEquals("0:09", Format.time(9_999))
        assertEquals("1:05", Format.time(65_000))
        assertEquals("59:59", Format.time(3_599_000))
        assertEquals("1:00:00", Format.time(3_600_000))
        assertEquals("11:02:05", Format.time(39_725_000))
        assertEquals("0:00", Format.time(-5))
    }

    @Test
    fun positions() {
        assertEquals(90_000L, Format.parsePosition("90"))
        assertEquals(90_000L, Format.parsePosition(" 1:30 "))
        assertEquals(3_690_000L, Format.parsePosition("1:01:30"))
        assertEquals(0L, Format.parsePosition("0"))
        assertNull(Format.parsePosition(""))
        assertNull(Format.parsePosition("abc"))
        assertNull(Format.parsePosition("1:"))
        assertNull(Format.parsePosition(":30"))
        assertNull(Format.parsePosition("1:75"))
        assertNull(Format.parsePosition("1:2:3:4"))
        assertNull(Format.parsePosition("-5"))
        assertNull(Format.parsePosition("99999999999"))
    }

    @Test
    fun apiAddresses() {
        // The public services, as they are written.
        assertEquals("https://api.openai.com/v1", Format.apiBase("https://api.openai.com/v1"))
        assertEquals("https://api.groq.com/openai/v1", Format.apiBase("https://api.groq.com/openai/v1/"))
        // At home: scheme and /v1 are optional.
        assertEquals("http://192.168.1.20:8000/v1", Format.apiBase("192.168.1.20:8000"))
        assertEquals("http://192.168.1.20:8000/v1", Format.apiBase(" http://192.168.1.20:8000/ "))
        assertEquals("http://mypc.local:11434/v1", Format.apiBase("mypc.local:11434/v1"))
        // A full endpoint pasted from the service's documentation.
        assertEquals("https://api.openai.com/v1", Format.apiBase("https://api.openai.com/v1/audio/speech"))
        assertEquals("https://api.openai.com/v1", Format.apiBase("https://api.openai.com/v1/chat/completions"))
        assertEquals("https://x.com/v1", Format.apiBase("https://x.com/v1/audio/transcriptions/"))
        assertEquals("https://x.com/v1", Format.apiBase("https://x.com/v1/models"))
        assertNull(Format.apiBase(""))
        assertNull(Format.apiBase("ftp://x"))
        assertNull(Format.apiBase("file:///etc/passwd"))
        assertNull(Format.apiBase("two words"))
        assertNull(Format.apiBase("http://user:pw@host"))
        assertNull(Format.apiBase("http://host?a=1"))
    }

    @Test
    fun cleartextWarning() {
        for (safe in listOf(
            "http://192.168.1.20:8765", "http://10.0.0.5:8765", "http://172.16.0.1:8765", "http://172.31.0.1:8765",
            "http://100.100.1.1:8765", "http://mypc.local:8765", "http://localhost:8765", "https://example.com",
        )) assertFalse(safe, Format.isUnsafeUrl(safe))
        for (unsafe in listOf(
            "http://example.com", "http://8.8.8.8:8765", "http://172.32.0.1:8765", "http://192.169.0.1:8765",
            "http://999.168.1.1:8765", "http://[2001:db8::1]:8765", "http://134744072", "http://0x08080808",
            "http://010.0.0.1", "http://nas.example.com", "http://local.example.com",
        )) assertTrue(unsafe, Format.isUnsafeUrl(unsafe))
    }

    @Test
    fun theKeyOnlyGoesInTheClearAtHome() {
        for (home in listOf(
            "http://192.168.1.20:8765/v1", "http://10.0.0.5/v1", "http://localhost:8765/v1", "http://mypc.local/v1",
            "http://nas:8000/v1", "http://mint:8000/v1", "http://router.home.arpa/v1", "http://box.internal/v1",
            "http://pc.tailnet.ts.net:8765/v1", "http://100.100.1.1:8765/v1", "http://[fd00::1]:8000/v1", "http://[::1]:8000/v1",
            "http://[fe80::1]:8000/v1",
        )) assertTrue(home, Format.mayCarryKey(home))
        assertTrue(Format.mayCarryKey("https://api.openai.com/v1"))
        assertTrue(Format.mayCarryKey("https://192.168.1.1/v1"))
        assertFalse(Format.mayCarryKey("http://api.openai.com/v1"))
        assertFalse(Format.mayCarryKey("http://8.8.8.8:8000/v1"))
    }

    @Test
    fun pagesInsideTheAppStayAtTheirAddress() {
        val start = "https://docs.google.com/forms/d/e/ABC/viewform"
        assertTrue(Links.staysInside(start, "https://docs.google.com/forms/d/e/ABC/formResponse"))
        assertTrue(Links.staysInside(start, "https://DOCS.google.com/forms/d/e/ABC/viewform?usp=form_confirm&a=1|2"))
        assertFalse(Links.staysInside(start, "https://docs.google.com/forms/d/e/OTHER/viewform"))
        assertFalse(Links.staysInside(start, "https://docs.google.com/document/d/1"))
        assertFalse(Links.staysInside(start, "https://accounts.google.com/forms/d/e/ABC/viewform"))
        assertFalse(Links.staysInside(start, "http://docs.google.com/forms/d/e/ABC/viewform"))
        assertFalse(Links.staysInside(start, "intent://docs.google.com#Intent;end"))
        assertFalse(Links.staysInside(start, "file:///etc/hosts"))
        assertFalse(Links.staysInside("", "https://docs.google.com/"))
    }

    @Test
    fun fixedAddresses() {
        assertTrue(Links.PRIVACY.startsWith("https://"))
        assertTrue(Regex("https://docs\\.google\\.com/forms/d/e/[^/]+/viewform").matches(Links.REPORT_FORM))
        // The answer lands next to the form, and "send another" comes back to it.
        assertTrue(Links.staysInside(Links.REPORT_FORM, Links.REPORT_FORM.replace("viewform", "formResponse")))
        assertTrue(Links.staysInside(Links.REPORT_FORM, Links.REPORT_FORM + "?usp=form_confirm"))
        assertFalse(Links.staysInside(Links.REPORT_FORM, "https://accounts.google.com/"))
        assertFalse(Links.staysInside(Links.REPORT_FORM, "https://docs.google.com/forms/d/e/OTHER/viewform"))
    }

    private val cues = listOf(Cue(1.0, 2.0, "a"), Cue(2.5, 3.0, "b"), Cue(3.0, 4.0, "c"))

    @Test
    fun cueAtTime() {
        assertEquals(-1, Format.cueAt(cues, 0.5))
        assertEquals(0, Format.cueAt(cues, 1.0))
        assertEquals(0, Format.cueAt(cues, 2.2))        // in the gap, the last one stays lit
        assertEquals(1, Format.cueAt(cues, 2.5))
        assertEquals(2, Format.cueAt(cues, 3.0))
        assertEquals(2, Format.cueAt(cues, 99.0))
        assertEquals(-1, Format.cueAt(emptyList(), 1.0))
    }

    @Test
    fun genericChapterTitles() {
        assertTrue(Format.chapterTitleIsGeneric(""))
        assertTrue(Format.chapterTitleIsGeneric("Capitulo 12"))
        assertFalse(Format.chapterTitleIsGeneric("Capitulo 12: La vuelta"))
        assertFalse(Format.chapterTitleIsGeneric("18. Find the Sweet Spot"))
    }

    @Test
    fun sizes() {
        assertEquals("1.5 GB", Format.size(1_500_000_000).replace(',', '.'))
        assertEquals("250 MB", Format.size(250_000_000))
        assertEquals("623 KB", Format.size(623_276))
        assertEquals("1 KB", Format.size(78))
    }

    @Test
    fun hostNames() {
        assertEquals("api.openai.com", Format.host("https://api.openai.com/v1"))
        assertEquals("192.168.1.20", Format.host("192.168.1.20:8766"))
        assertEquals("mypc.local", Format.host("mypc.local"))
    }

    @Test
    fun whatTheListOfSentencesDoes() {
        val pause = 4_500L
        // Without a list there is nothing to scroll: asking would wait for its first layout and arrive late.
        assertEquals(Format.CueScroll.NONE, Format.cueScroll(0, -1, 60_000, pause))
        assertEquals(Format.CueScroll.TOP, Format.cueScroll(40, -1, 60_000, pause))
        assertEquals(Format.CueScroll.TOP, Format.cueScroll(40, -1, 0, pause))        // a new chapter, even just after a touch
        assertEquals(Format.CueScroll.FOLLOW, Format.cueScroll(40, 13, 60_000, pause))
        assertEquals(Format.CueScroll.NONE, Format.cueScroll(40, 13, 1_000, pause))   // reading elsewhere
        assertEquals(Format.CueScroll.NONE, Format.cueScroll(40, 13, pause, pause))
    }

    @Test
    fun slugs() {
        assertEquals("nandu-el-grande", Format.slug("Ñandú el Grande!"))
        assertEquals("evil", Format.slug("../../evil"))
        assertEquals("book", Format.slug("漢字"))
        assertTrue(Format.slug("a".repeat(500)).length <= 120)
    }
}
