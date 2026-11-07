package com.javimetallab.storyteller

import com.javimetallab.storyteller.engine.Epub
import com.javimetallab.storyteller.engine.Pages
import com.javimetallab.storyteller.engine.TextPrep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The page the listening is on. Same sample book and same numbers as tests/test_paginas.py of the desktop app. */
class PagesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val xhtml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
        "xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>No se lee</title></head><body>%s</body></html>"
    private val cap1 = "<p>Autor Primero</p><h1>Capítulo uno</h1>" +
        "<p><span epub:type=\"pagebreak\" id=\"p1\" title=\"1\"/>Érase una vez, en un lugar muy lejano, una niña que leía.</p>" +
        "<p>Leía de día y de noche.<span epub:type=\"pagebreak\" id=\"p2\" title=\"2\"/> Y nunca se cansaba de leer.</p>"
    private val cap2 = "<h2>Dos</h2><p>El segundo capítulo empieza aquí, con otra historia.</p>" +
        "<p><span role=\"doc-pagebreak\" id=\"p3\">3</span>La historia sigue en la página tres y acaba.</p>"
    private val pageList = "<nav epub:type=\"page-list\"><ol><li><a href=\"Text/cap1.xhtml#p1\">1</a></li>" +
        "<li><a href=\"Text/cap1.xhtml#p2\">2</a></li><li><a href=\"Text/cap2.xhtml#p3\">3</a></li></ol></nav>"
    private val ncxList = "<pageList><pageTarget id=\"t1\" value=\"1\" type=\"normal\"><navLabel><text>1</text></navLabel>" +
        "<content src=\"Text/cap1.xhtml#p1\"/></pageTarget><pageTarget id=\"t2\" value=\"2\" type=\"normal\">" +
        "<navLabel><text>2</text></navLabel><content src=\"Text/cap1.xhtml#p2\"/></pageTarget>" +
        "<pageTarget id=\"t3\" value=\"3\" type=\"normal\"><navLabel><text>3</text></navLabel>" +
        "<content src=\"Text/cap2.xhtml#p3\"/></pageTarget></pageList>"

    private val cues1 = listOf(
        "Autor Primero.", "Érase una vez, en un lugar muy lejano,", "una niña que leía.",
        "Leía de día y de noche.", "Y nunca se cansaba de leer.",
    )
    private val cues2 = listOf("El segundo capítulo empieza aquí,", "con otra historia.", "3 La historia sigue en la página tres y acaba.")
    private val offs1 = listOf(0, 12, 41, 55, 72)
    private val offs2 = listOf(93, 121, 136)

    /** variant: nav (EPUB3 page-list), ncx (only the EPUB2 pageList), inline (only the marks in the text), none. */
    private fun sample(variant: String, file: File = File(tmp.root, "$variant.epub")): File {
        var c1 = cap1
        var c2 = cap2
        if (variant == "none") {
            c1 = c1.replace("<span epub:type=\"pagebreak\" id=\"p1\" title=\"1\"/>", "")
                .replace("<span epub:type=\"pagebreak\" id=\"p2\" title=\"2\"/>", "")
            c2 = c2.replace("<span role=\"doc-pagebreak\" id=\"p3\">3</span>", "3")
        }
        var nav = "<nav epub:type=\"toc\"><ol><li><a href=\"Text/cap1.xhtml\">Uno</a></li></ol></nav>"
        if (variant == "nav") nav += pageList
        val ncx = "<?xml version=\"1.0\" encoding=\"utf-8\"?><ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">" +
            "<navMap><navPoint id=\"n1\"><navLabel><text>Uno</text></navLabel><content src=\"Text/cap1.xhtml\"/></navPoint>" +
            "</navMap>" + (if (variant == "nav" || variant == "ncx") ncxList else "") + "</ncx>"
        val opf = "<?xml version=\"1.0\" encoding=\"utf-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Muestra</dc:title></metadata><manifest>" +
            "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
            "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
            "<item id=\"c1\" href=\"Text/cap1.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "<item id=\"c2\" href=\"Text/cap2.xhtml\" media-type=\"application/xhtml+xml\"/>" +
            "</manifest><spine toc=\"ncx\"><itemref idref=\"nav\"/><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>"
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                    "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>" +
                    "</rootfiles></container>",
            )
            put("OEBPS/content.opf", opf)
            put("OEBPS/nav.xhtml", xhtml.format(nav))
            put("OEBPS/toc.ncx", ncx)
            put("OEBPS/Text/cap1.xhtml", xhtml.format(c1))
            put("OEBPS/Text/cap2.xhtml", xhtml.format(c2))
        }
        return file
    }

    @Test
    fun lettersCountTheSameEverywhere() {
        assertEquals("SíÑu35名前", Pages.letters("¿Sí? «Ñu», 3,5 €… 名前。"))
        assertEquals("x", Pages.letters("x² ½ _ -"))                  // neither superscripts nor fractions: nor does the desktop
    }

    @Test
    fun thePrintedBooksPages() {
        for (variant in listOf("nav", "ncx", "inline")) {
            val bp = Pages.fromEpub(sample(variant))!!
            assertTrue(variant, bp.real)
            assertEquals(variant, 3, bp.total)
            assertEquals(variant, 172, bp.totalLetters)
            val o1 = bp.chapterOffsets(1, cues1)!!
            val o2 = bp.chapterOffsets(2, cues2)!!
            assertEquals(variant, offs1, o1)
            assertEquals(variant, offs2, o2)
            assertEquals(variant, listOf("1", "1", "1", "1", "2"), o1.map(bp::page))
            assertEquals(variant, listOf("2", "2", "3"), o2.map(bp::page))
            assertEquals(variant, 54, bp.percent(o2[0]))
        }
    }

    @Test
    fun withoutPageNumbersFixedApproximatePages() {
        val f = sample("none")
        val bp = Pages.fromEpub(f, perPage = 50)!!
        assertFalse(bp.real)
        assertEquals(172, bp.totalLetters)
        assertEquals(4, bp.total)
        val o2 = bp.chapterOffsets(2, cues2)!!
        assertEquals(offs2, o2)
        assertEquals(listOf("2", "3", "3"), o2.map(bp::page))
        // The same sentences cut another way fall on the same pages.
        val other = bp.chapterOffsets(
            2, listOf("El segundo capítulo empieza aquí, con otra historia.", "3 La historia sigue", "en la página tres y acaba."),
        )!!
        assertEquals(offs2[0], other[0])
        assertEquals("3", bp.page(other[1]))
        assertEquals(1, Pages.fromEpub(f)!!.total)                    // 1,500 letters a page
    }

    @Test
    fun aChapterThatIsNotInTheEpub() {
        val bp = Pages.fromEpub(sample("nav"))!!
        assertNull(bp.chapterOffsets(1, listOf("Nada de esto está en el libro.")))
        assertNull(bp.chapterOffsets(1, emptyList()))
    }

    @Test
    fun aSentenceThatDoesNotMatchDoesNotMoveTheNextOnes() {
        val bp = Pages.fromEpub(sample("nav"))!!
        val o = bp.chapterOffsets(1, listOf(cues1[0], "Texto cambiado que no está") + cues1.drop(1))!!
        assertEquals(0, o[0])
        assertEquals(offs1.drop(1), o.drop(2))
    }

    @Test
    fun withoutAnEpubTheSubtitles() {
        val dir = tmp.newFolder("imported")
        val bp = Pages.forBook(dir) { listOf(listOf("a".repeat(30), "b".repeat(40))) }
        assertFalse(bp.real)
        assertEquals(70, bp.totalLetters)
        assertEquals(listOf(0, 30), bp.chapterOffsets(1, listOf("a".repeat(30), "b".repeat(40))))
    }

    @Test
    fun withAnEpubItIsUsed() {
        val dir = tmp.newFolder("book")
        sample("inline", File(dir, "source.epub"))
        assertTrue(Pages.forBook(dir) { emptyList() }.real)
    }

    @Test
    fun aBrokenEpub() {
        val f = File(tmp.root, "broken.epub").apply { writeText("not a zip") }
        assertNull(Pages.fromEpub(f))
    }

    @Test
    fun withTheEnginesOwnCutting() {
        // The sentences the app narrates fall one after another, with no gaps or jumps.
        for (variant in listOf("nav", "none")) {
            val f = sample(variant, File(tmp.root, "m-$variant.epub"))
            val bp = Pages.fromEpub(f)!!
            for ((i, ch) in Epub.read(f).chapters.withIndex()) {
                val sents = TextPrep.splitChunks(ch.text).map { it.text }
                val offs = bp.chapterOffsets(i + 1, sents)
                assertNotNull(offs)
                for (j in 0 until sents.size - 1) assertEquals(Pages.letters(sents[j]).length, offs!![j + 1] - offs[j])
            }
        }
    }
}
