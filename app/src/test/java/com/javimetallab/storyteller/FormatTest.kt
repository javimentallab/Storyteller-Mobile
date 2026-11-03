package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Cue
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.data.Links
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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

    /** The list of the desktop player, `_speeds` in storyteller.py: both apps offer the same speeds. */
    @Test
    fun theSpeedsAreTheDesktopOnes() {
        val desktop = listOf(
            0.75f, 0.8f, 0.85f, 0.9f, 0.95f, 1f, 1.05f, 1.1f, 1.15f, 1.2f, 1.25f, 1.3f, 1.35f,
            1.4f, 1.45f, 1.5f, 1.55f, 1.6f, 1.65f, 1.7f, 1.75f, 1.8f, 1.85f, 1.9f, 1.95f, 2f,
        )
        assertEquals(desktop, Format.SPEEDS)
        // A speed saved by version 1.0, with its shorter list, must still be found.
        assertTrue(Format.SPEEDS.containsAll(listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)))
    }

    @Test
    fun speedsAreWrittenAsInTheUsersLanguage() {
        val spanish = Locale.forLanguageTag("es")
        assertEquals("1×", Format.speedLabel(1f, spanish))
        assertEquals("2×", Format.speedLabel(2f, spanish))
        assertEquals("0,75×", Format.speedLabel(0.75f, spanish))
        assertEquals("1,05×", Format.speedLabel(1.05f, spanish))
        assertEquals("1,1×", Format.speedLabel(1.1f, spanish))       // no zero at the end, as on the desktop
        assertEquals("1.25×", Format.speedLabel(1.25f, Locale.ENGLISH))
        // Every language of the app, every speed: its decimal mark, ordinary digits, and never longer than "1,25×".
        val marks = mapOf(
            "en" to ".", "es" to ",", "pt" to ",", "fr" to ",", "de" to ",", "it" to ",", "nl" to ",", "pl" to ",",
            "ru" to ",", "uk" to ",", "zh-CN" to ".", "ja" to ".", "ko" to ".", "th" to ".", "hi" to ".",
        )
        for ((tag, mark) in marks) for (s in Format.SPEEDS) {
            val plain = "%.2f".format(Locale.ROOT, s).trimEnd('0').trimEnd('.')
            val label = Format.speedLabel(s, Locale.forLanguageTag(tag))
            assertEquals("$tag $s", plain.replace(".", mark) + "×", label)
            assertTrue(label, label.length <= 5)
        }
    }

    @Test
    fun theSpeedMenuOpensWithTheCurrentOneInTheMiddle() {
        // 26 rows of 100 px with 400 px in view.
        assertEquals(350, Format.centredScroll(5, 26, 2600, 400))    // the row of 1×: 500-600, in the middle of 350-750
        assertEquals(0, Format.centredScroll(0, 26, 2600, 400))      // nothing above the first
        assertEquals(2200, Format.centredScroll(25, 26, 2600, 400))  // nor below the last
        assertEquals(0, Format.centredScroll(25, 26, 2600, 3000))    // everything fits
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
