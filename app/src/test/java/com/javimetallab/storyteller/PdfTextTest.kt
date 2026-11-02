package com.javimetallab.storyteller

import com.javimetallab.storyteller.engine.Docs
import com.javimetallab.storyteller.engine.Epub
import com.javimetallab.storyteller.engine.PdfBook
import com.javimetallab.storyteller.engine.PdfChar
import com.javimetallab.storyteller.engine.PdfDoc
import com.javimetallab.storyteller.engine.PdfLayout
import com.javimetallab.storyteller.engine.PdfPage
import com.javimetallab.storyteller.engine.Translation
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/** sample.golden.json comes out of the desktop engine (tools/pdf_fixtures.py): the same blocks and chapters here. */
class PdfTextTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val golden by lazy {
        JSONObject(javaClass.getResourceAsStream("/sample.golden.json")!!.readBytes().toString(Charsets.UTF_8))
    }

    private fun resource(name: String): File {
        val f = tmp.newFile(name)
        f.writeBytes(javaClass.getResourceAsStream("/$name")!!.readBytes())
        return f
    }

    /** Glyphs half an em wide, the way a line of text is written, with its descent below the baseline. */
    private fun run(text: String, x: Double, baseline: Double, size: Double = 11.0): List<PdfChar> =
        text.mapIndexed { i, c ->
            val left = x + i * size * 0.5
            PdfChar(c.toString(), left, baseline - 0.2 * size, left + size * 0.5, baseline + 0.8 * size)
        }

    private fun book(vararg pages: List<PdfChar>, height: Double = 800.0) =
        PdfBook(pages.asSequence().map { PdfPage(it, 600.0, height) })

    private fun texts(b: PdfBook) = b.blocks.map { it.text }

    @Test
    fun aGapInALineIsASpaceAndAWideOneIsAnotherLine() {
        val near = run("uno", 50.0, 700.0) + run("dos", 50.0 + 3 * 5.5 + 3.0, 700.0)
        assertEquals(listOf("uno dos"), PdfLayout.boxes(near, 600.0, 800.0).single().lines.map { it.text.toString() })
        val far = run("uno", 50.0, 700.0) + run("dos", 300.0, 700.0)
        assertEquals(2, PdfLayout.boxes(far, 600.0, 800.0).size)
    }

    @Test
    fun closeLinesAreABlockAndAGapStartsAnother() {
        val b = book(
            run("primera linea del bloque", 50.0, 700.0) + run("segunda linea del bloque", 50.0, 686.0) +
                run("otro parrafo aparte", 50.0, 650.0),
        )
        assertEquals(listOf("primera linea del bloque segunda linea del bloque", "otro parrafo aparte"), texts(b))
    }

    @Test
    fun columnsAreReadOneAfterTheOther() {
        // written across, line by line, as many PDFs do
        val b = book((0 until 6).flatMap { run("columna izquierda $it", 40.0, 700.0 - it * 13) + run("columna derecha $it", 320.0, 700.0 - it * 13) })
        assertEquals(2, b.blocks.size)
        assertTrue(b.blocks[0].text.startsWith("columna izquierda 0 columna izquierda 1"))
        assertTrue(b.blocks[1].text.startsWith("columna derecha 0 columna derecha 1"))
    }

    @Test
    fun aLineCutWithAHyphenIsJoined() {
        val b = book(run("una tradu-", 50.0, 700.0) + run("ccion larga y Madrid-", 50.0, 686.0) + run("Barajas", 50.0, 672.0))
        assertEquals("una traduccion larga y Madrid- Barajas", b.blocks.single().text)
    }

    @Test
    fun ligaturesComeApartAndMissingGlyphsVanish() {
        val b = book(run("la ﬁesta del ﬂan", 50.0, 700.0) + listOf(PdfChar("(cid:7)", 200.0, 697.8, 205.5, 708.8)))
        assertEquals("la fiesta del flan", b.blocks.single().text)
    }

    /** It comes after its paragraph in the file, and only its position says where it belongs. */
    @Test
    fun aDropCapGoesToTheBlockOnItsRight() {
        val b = book(
            run("otro bloque suelto arriba del todo", 50.0, 760.0) +
                run("acia el norte se alza un faro", 90.0, 700.0) + run("de piedra gris y puerta azul", 90.0, 686.0) +
                run("H", 50.0, 680.0, 36.0),
        )
        assertEquals(listOf("otro bloque suelto arriba del todo", "Hacia el norte se alza un faro de piedra gris y puerta azul"), texts(b))
    }

    @Test
    fun aBigLetterWithNothingOnItsRightStaysAlone() {
        val b = book(run("un parrafo normal de cuerpo", 200.0, 700.0) + run("X", 50.0, 400.0, 36.0))
        assertEquals(2, b.blocks.size)
        assertTrue(b.blocks.any { it.text == "X" })
    }

    @Test
    fun aHeadingIsTwelveTenthsOfTheBody() {
        val body = (0 until 5).flatMap { run("cuerpo $it", 50.0, 600.0 - it * 40) }
        val heading = run("Esto es un titulo", 50.0, 700.0, 11.0 * 1.21)
        val notYet = run("Esto no lo es", 50.0, 750.0, 11.0 * 1.19)
        val b = book(notYet + heading + body)
        val chapters = b.chapters("Libro")
        assertEquals(listOf("Libro", "Esto es un titulo"), chapters.map { it.title })
        assertEquals(listOf("p" to "Esto no lo es"), chapters[0].paras)
        assertEquals(5, chapters[1].paras.size)
    }

    @Test
    fun aHeadingWithNoTextYetStaysInItsChapter() {
        val b = book(
            run("Capitulo uno", 50.0, 760.0, 20.0) + run("Un subtitulo", 50.0, 720.0, 14.0) +
                (0 until 5).flatMap { run("cuerpo $it", 50.0, 600.0 - it * 40) },
        )
        val c = b.chapters("Libro").single()
        assertEquals("Capitulo uno", c.title)
        assertEquals("h" to "Un subtitulo", c.paras.first())
    }

    @Test
    fun runningHeadsAndPageNumbersGo() {
        val pages = (1..6).map { n ->
            run("Cabecera del libro", 50.0, 790.0, 8.0) + run("Pagina $n de texto corriente y largo", 50.0, 400.0) + run("$n", 300.0, 10.0, 8.0)
        }
        val b = book(*pages.toTypedArray())
        val paras = b.chapters("Libro").flatMap { it.paras.map { p -> p.second } }
        assertEquals((1..6).map { "Pagina $it de texto corriente y largo" }, paras)
    }

    @Test
    fun aLongBookWithNoHeadingsGetsAChapterEveryTenPages() {
        val pages = (1..25).map { run("Pagina numero $it del libro", 50.0, 400.0) }
        val c = book(*pages.toTypedArray()).chapters("Libro")
        assertEquals(listOf("Libro (1)", "Libro (2)", "Libro (3)"), c.map { it.title })
        assertEquals(listOf(10, 10, 5), c.map { it.paras.size })
    }

    @Test
    fun sampleLikeTheDesktopEngine() {
        val want = golden.getJSONObject("sample")
        val d = PdfDoc(resource("sample.pdf"), "es", "Muestra")
        val blocks = want.getJSONArray("blocks")
        assertEquals(want.getInt("pages"), d.book.pages)
        assertEquals((0 until blocks.length()).map { blocks.getJSONObject(it).getString("text") }, d.book.blocks.map { it.text })
        d.book.blocks.forEachIndexed { i, b ->
            val w = blocks.getJSONObject(i)
            assertEquals(w.getInt("page"), b.page)
            assertEquals(w.getDouble("size"), b.size, 0.01)
            assertEquals(w.getDouble("top"), b.top, 0.01)
            assertEquals(w.getDouble("bottom"), b.bottom, 0.01)
            assertEquals(w.getDouble("height"), b.height, 0.01)
        }
        assertEquals(chaptersOf(want.getJSONArray("chapters")), d.book.chapters("Muestra").map { it.title to it.paras })
    }

    private fun chaptersOf(a: JSONArray) = (0 until a.length()).map { i ->
        val c = a.getJSONObject(i)
        val p = c.getJSONArray("paras")
        c.getString("title") to (0 until p.length()).map { p.getJSONArray(it).getString(0) to p.getJSONArray(it).getString(1) }
    }

    @Test
    fun aScanHasNothingToTranslate() {
        assertEquals(0, golden.getJSONObject("scan").getJSONArray("blocks").length())
        assertTrue(Docs.open(resource("scan.pdf"), "es").units.isEmpty())
    }

    @Test
    fun theEpubIsValidAndTheAppCanNarrateIt() {
        val d = Docs.open(resource("sample.pdf"), "es", "Muestra (en)")
        for (u in d.units) u.apply(u.text.uppercase() + " <&> «ok»\u0002")
        val out = File(tmp.root, "out.epub")
        d.save(out)
        ZipFile(out).use { z ->
            val first: ZipEntry = z.entries().nextElement()
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED, first.method)
            val xml = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            for (e in z.entries()) {
                if (e.name.endsWith("xhtml") || e.name.endsWith("opf") || e.name.endsWith("container.xml")) {
                    z.getInputStream(e).use { xml.parse(it) }     // strict: throws if it isn't well formed
                }
            }
        }
        val book = Epub.read(out)
        assertEquals("Muestra", book.title)
        assertEquals("es", book.language)
        assertEquals(2, book.chapters.size)
        assertTrue(book.chapters[0].text.contains("HACIA EL NORTE DE LA ISLA"))
        assertTrue(book.chapters[1].text.contains("<&> «ok»"))
    }

    @Test
    fun theUnitsAreTheSameEveryTimeSoAStoppedJobResumes() {
        val f = resource("sample.pdf")
        val keys = { PdfDoc(f, "es", "M").units.map { Translation.key(it.text) } }
        assertEquals(keys(), keys())
    }

    @Test
    fun anUntranslatedDocumentKeepsItsText() {
        val d = Docs.open(resource("sample.pdf"), "es", "M")
        val out = File(tmp.root, "out.epub")
        d.save(out)
        assertTrue(Epub.read(out).chapters[0].text.contains("Hacia el norte de la isla"))
    }

    private fun simplePdf(file: File, password: String? = null, write: (PDDocument, PDPageContentStream) -> Unit) {
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            PDPageContentStream(doc, page).use { write(doc, it) }
            if (password != null) doc.protect(StandardProtectionPolicy("dueno", password, AccessPermission()))
            doc.save(file)
        }
    }

    private fun line(c: PDPageContentStream, font: com.tom_roush.pdfbox.pdmodel.font.PDFont, text: String) {
        c.beginText()
        c.setFont(font, 12f)
        c.newLineAtOffset(72f, 700f)
        c.showText(text)
        c.endText()
    }

    /** Bouncy Castle isn't in the app: the usual locks don't need it. */
    @Test
    fun aPdfLockedOnlyByItsOwnerIsRead() {
        val f = tmp.newFile("locked.pdf")
        simplePdf(f, password = "") { _, c -> line(c, PDType1Font.HELVETICA, "Texto bajo llave") }
        assertEquals(listOf("Texto bajo llave"), Docs.open(f, "es").units.map { it.text })
    }

    @Test(expected = IOException::class)
    fun aPdfWithAPasswordCantBeRead() {
        val f = tmp.newFile("password.pdf")
        simplePdf(f, password = "secreta") { _, c -> line(c, PDType1Font.HELVETICA, "Texto bajo llave") }
        Docs.open(f, "es")
    }

    @Test
    fun anEmbeddedUnicodeFontIsRead() {
        val f = tmp.newFile("embedded.pdf")
        simplePdf(f) { doc, c ->
            val ttf = javaClass.getResourceAsStream("/com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")!!
            line(c, PDType0Font.load(doc, ttf), "Año niño, Привет")
        }
        assertEquals(listOf("Año niño, Привет"), Docs.open(f, "es").units.map { it.text })
    }

    /** It must be an ordinary failure, with its message for the user, even without Bouncy Castle. */
    @Test
    fun aPdfLockedToACertificateFailsLikeAnyOtherBadFile() {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>",
            "<< /Filter /Adobe.PubSec /SubFilter /adbe.pkcs7.s4 /V 2 /Length 128 /Recipients [(x)] >>",
        )
        val pdf = StringBuilder("%PDF-1.5\n")
        val offsets = objects.mapIndexed { i, o ->
            pdf.length.also { pdf.append("${i + 1} 0 obj\n$o\nendobj\n") }
        }
        val xref = pdf.length
        pdf.append("xref\n0 5\n0000000000 65535 f \n")
        offsets.forEach { pdf.append("%010d 00000 n \n".format(it)) }
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R /Encrypt 4 0 R /ID [(a) (a)] >>\nstartxref\n$xref\n%%EOF\n")
        val f = tmp.newFile("cert.pdf")
        f.writeText(pdf.toString())
        try {
            Docs.open(f, "es")
            assertFalse("it should not open", true)
        } catch (_: IOException) {
            // what TranslateTask turns into "can't read that file"
        }
    }

    @Test(expected = IOException::class)
    fun aBrokenFileIsAnOrdinaryFailure() {
        val f = tmp.newFile("broken.pdf")
        f.writeText("this is not a pdf at all")
        Docs.open(f, "es")
    }

    /** Stopping while it reads a long PDF has to work: the check comes between pages. */
    @Test
    fun readingCanBeStoppedBetweenPages() {
        class Stop : RuntimeException()
        var seen = 0
        try {
            PdfDoc(resource("sample.pdf"), "es", "M") {
                if (++seen == 3) throw Stop()
            }
            assertFalse("it should stop", true)
        } catch (_: Stop) {
            assertEquals(3, seen)
        }
    }

    /**
     * Not a test of the build: with PDF_DUMP_LIST and PDF_DUMP_OUT set, dumps real PDFs to compare
     * with the desktop engine. They are the user's books, and stay out of the repo.
     */
    @Test
    fun dumpRealPdfs() {
        val list = System.getenv("PDF_DUMP_LIST")
        val out = System.getenv("PDF_DUMP_OUT")
        assumeTrue(list != null && out != null)
        val only = System.getenv("PDF_DUMP_ONLY")?.split(",")?.map { it.trim().toInt() }?.toSet()
        File(list!!).readLines().forEachIndexed { i, path ->
            if (only != null && i !in only) return@forEachIndexed
            val dest = File(out!!, "%02d.json".format(i))
            val r = JSONObject()
            val t = System.nanoTime()
            try {
                val d = PdfDoc(File(path), "es", "T")
                r.put("secs", (System.nanoTime() - t) / 1e9)
                r.put("pages", d.book.pages)
                r.put("blocks", JSONArray(d.book.blocks.map { b ->
                    JSONObject().put("page", b.page).put("height", b.height).put("top", b.top).put("bottom", b.bottom)
                        .put("size", b.size).put("text", b.text)
                }))
                r.put("chapters", JSONArray(d.book.chapters("T").map { c ->
                    JSONObject().put("title", c.title).put("paras", JSONArray(c.paras.map { JSONArray(listOf(it.first, it.second)) }))
                }))
                r.put("heapMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000)
            } catch (e: Throwable) {
                r.put("error", e.toString())
            }
            dest.writeText(r.toString(), Charsets.UTF_8)
        }
    }
}
