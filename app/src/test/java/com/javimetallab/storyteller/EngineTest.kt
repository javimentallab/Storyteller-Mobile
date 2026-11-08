package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Segment
import com.javimetallab.storyteller.data.Transcript
import com.javimetallab.storyteller.data.Word
import com.javimetallab.storyteller.engine.Chunker
import com.javimetallab.storyteller.engine.Docs
import com.javimetallab.storyteller.data.PronStore
import com.javimetallab.storyteller.engine.Epub
import com.javimetallab.storyteller.engine.Pcm
import com.javimetallab.storyteller.engine.Pron
import com.javimetallab.storyteller.engine.Qc
import com.javimetallab.storyteller.engine.TextPrep
import com.javimetallab.storyteller.engine.Timing
import com.javimetallab.storyteller.engine.Translation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** golden.json comes out of the desktop engine: the phone has to cut and clean the text the same way. */
class EngineTest {
    private val golden by lazy {
        JSONObject(javaClass.getResourceAsStream("/golden.json")!!.readBytes().toString(Charsets.UTF_8))
    }

    @Test
    fun sentencesLikeTheDesktopEngine() {
        val cases = golden.getJSONArray("split")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("out").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("case $i", want, TextPrep.splitSentences(c.getString("in")))
        }
        assertTrue(cases.length() > 5)
    }

    @Test
    fun chunksLikeTheDesktopEngine() {
        val close = mapOf("coma" to TextPrep.Close.CLAUSE, "frase" to TextPrep.Close.SENTENCE, "parrafo" to TextPrep.Close.PARAGRAPH)
        val cases = golden.getJSONArray("chunks")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("out").let { a ->
                (0 until a.length()).map { k -> TextPrep.Chunk(a.getJSONArray(k).getString(0), close.getValue(a.getJSONArray(k).getString(1))) }
            }
            assertEquals("case $i: ${c.getString("in")}", want, TextPrep.splitChunks(c.getString("in")))
        }
        assertTrue(cases.length() > 10)
        val pauses = golden.getJSONObject("pauses")
        for ((key, value) in close) assertEquals(key, pauses.getDouble(key), value.pause, 0.0)
    }

    @Test
    fun chunksOfEachLanguageLikeTheDesktopEngine() {
        val close = mapOf("coma" to TextPrep.Close.CLAUSE, "frase" to TextPrep.Close.SENTENCE, "parrafo" to TextPrep.Close.PARAGRAPH)
        val cases = golden.getJSONArray("chunks_lang")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("out").let { a ->
                (0 until a.length()).map { k -> TextPrep.Chunk(a.getJSONArray(k).getString(0), close.getValue(a.getJSONArray(k).getString(1))) }
            }
            assertEquals("${c.getString("lang")} ${c.getString("in").take(40)}", want, TextPrep.splitChunks(c.getString("in"), c.getString("lang")))
        }
        assertTrue(cases.length() > 5)
    }

    /** A whole book and thousands of random texts, made by src/test/tools/golden_chunks.py --big: only with GOLDEN_BIG=<file>. */
    @Test
    fun aWholeBookLikeTheDesktopEngine() {
        val path = System.getenv("GOLDEN_BIG") ?: return
        val close = mapOf("coma" to TextPrep.Close.CLAUSE, "frase" to TextPrep.Close.SENTENCE, "parrafo" to TextPrep.Close.PARAGRAPH)
        val cases = org.json.JSONArray(File(path).readText(Charsets.UTF_8))
        var pieces = 0
        val wrong = ArrayList<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("out").let { a ->
                (0 until a.length()).map { k -> TextPrep.Chunk(a.getJSONArray(k).getString(0), close.getValue(a.getJSONArray(k).getString(1))) }
            }
            pieces += want.size
            if (TextPrep.splitChunks(c.getString("in"), c.getString("lang")) != want) wrong += "$i ${c.getString("lang")} ${c.getString("in").take(60)}"
        }
        println("GOLDEN_BIG: ${cases.length()} texts, $pieces pieces, ${wrong.size} different")
        assertEquals(wrong.take(5).joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun theTextForTheVoiceLikeTheDesktopEngine() {
        val spellings = golden.getJSONObject("spell")
        val cases = golden.getJSONArray("voice")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val lang = c.getString("lang")
            val spell = spellings.optJSONObject(lang)
            val pron = c.optJSONObject("pron")?.let { Pron.of(it, lang) } ?: Pron.NONE
            assertEquals(
                "$lang: ${c.getString("in")}", c.getString("out"),
                TextPrep.forVoice(c.getString("in"), lang, { n -> spell?.optString(n.toString())?.ifEmpty { null } ?: n.toString() }, pron),
            )
        }
        assertTrue(cases.length() > 10)
    }

    @Test
    fun theVerdictLikeTheDesktopEngine() {
        val words = golden.getJSONArray("qc_words")
        for (i in 0 until words.length()) {
            val c = words.getJSONObject(i)
            val want = c.getJSONArray("out").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals(c.getString("in"), want, Qc.words(c.getString("in")))
        }
        val ratios = golden.getJSONArray("qc_ratio")
        for (i in 0 until ratios.length()) {
            val c = ratios.getJSONObject(i)
            assertEquals("${c.getString("a")} ${c.getString("b")}", c.getDouble("r"), Qc.ratio(c.getString("a"), c.getString("b")), 1e-9)
        }
        val cases = golden.getJSONArray("qc")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val v = Qc.eval(c.getString("ref"), c.getString("hyp"), fault = c.optString("fault"))
            val name = "${c.getString("ref")} / ${c.getString("hyp")}"
            assertEquals(name, c.getDouble("recall"), v.recall, 1e-9)
            assertEquals(name, c.getBoolean("ok"), v.ok)
            assertEquals(name, c.getDouble("score"), v.score, 1e-9)
        }
        assertTrue(cases.length() > 8)
    }

    @Test
    fun voiceWithoutWordsAtTheEndIsAFault() {
        val rate = 24_000
        val voice = ShortArray(rate * 3) { i -> (if (i % 2 == 0) 8000 else -8000).toShort() }
        assertTrue(Qc.audioFault("hola", Pcm(voice, rate), 1.0, Qc.Pace()).isNotEmpty())
        assertEquals("", Qc.audioFault("hola", Pcm(voice, rate), 2.95, Qc.Pace()))
        val quiet = ShortArray(rate * 3) { i -> if (i < rate) (if (i % 2 == 0) 8000 else -8000).toShort() else 0 }
        assertEquals("", Qc.audioFault("hola", Pcm(quiet, rate), 1.0, Qc.Pace()))
        val pace = Qc.Pace()
        val text = "quince letras justas"
        repeat(8) { pace.note(text, Pcm(ShortArray(rate), rate)) }
        assertEquals("", Qc.audioFault(text, Pcm(ShortArray(rate), rate), null, pace))
        assertTrue(Qc.audioFault(text, Pcm(ShortArray(rate * 3), rate), null, pace).isNotEmpty())
    }

    @Test
    fun theDictionaryIsOfEachLanguage() {
        val root = JSONObject()
            .put("pron", JSONObject().put("software", "sófwer"))
            .put("langs", JSONObject().put("en", JSONObject().put("pron", JSONObject().put("nginx", "engine x")).put("say_letters", org.json.JSONArray(listOf("SQL")))))
        assertEquals("el sófwer de nginx.", TextPrep.forVoice("El software de nginx.", "es", { "$it" }, Pron.of(root, "es")))
        assertEquals("the software of engine x, s q l.", TextPrep.forVoice("The software of nginx, SQL.", "en", { "$it" }, Pron.of(root, "en")))
        assertEquals("le software.", TextPrep.forVoice("Le software.", "fr", { "$it" }, Pron.of(root, "fr")))
    }

    @Test
    fun theDictionaryScreenGoesAndComesBack() {
        val root = JSONObject().put("pron", JSONObject().put("software", "sófwer")).put("say_letters", org.json.JSONArray(listOf("USB")))
        val t = PronStore.texts(root, "es")
        assertEquals("software = sófwer", t.words)
        assertEquals("USB", t.letters)
        PronStore.withTexts(root, "en", PronStore.Texts("nginx = engine x\nno vale\n= tampoco\n", "SQL, API\nCSS", ""))
        val e = PronStore.texts(root, "en")
        assertEquals("nginx = engine x", e.words)
        assertEquals("SQL, API, CSS", e.letters)
        assertEquals("software = sófwer", PronStore.texts(root, "es").words)
        PronStore.withTexts(root, "es", PronStore.Texts("email = ímeil", "", "NASA"))
        assertEquals("email = ímeil", PronStore.texts(root, "es").words)
        assertEquals("nginx = engine x", PronStore.texts(root, "en").words)
        assertEquals("", PronStore.texts(JSONObject(), "fr").words)
    }

    @Test
    fun aNonLatinBookIsNotLeftWithoutText() {
        assertEquals(listOf("Привет, мир.", "Хорошо."), TextPrep.splitChunks("Привет, мир. Хорошо.").map { it.text })
    }

    @Test
    fun punctuationLikeTheDesktopEngine() {
        val cases = golden.getJSONArray("punct")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("case $i: ${c.getString("in")}", c.getString("out"), TextPrep.normalizePunct(c.getString("in")))
        }
        assertTrue(cases.length() > 5)
    }

    private fun epub(): File {
        val f = File.createTempFile("book", ".epub")
        ZipOutputStream(f.outputStream()).use { z ->
            fun put(n: String, s: String) {
                z.putNextEntry(ZipEntry(n))
                z.write(s.toByteArray())
                z.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/c.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            put(
                "OEBPS/c.opf",
                """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>Título del libro</dc:title><dc:language>es-ES</dc:language></metadata>
                <manifest><item id="n" href="nav.xhtml" media-type="application/xhtml+xml"/><item id="a" href="text/a%20b.xhtml" media-type="application/xhtml+xml"/><item id="s" href="s.xhtml" media-type="application/xhtml+xml"/><item id="t" href="tiny.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="n"/><itemref idref="s"/><itemref idref="a"/><itemref idref="t"/></spine></package>""",
            )
            put("OEBPS/nav.xhtml", "<html><body><p>Índice que no se lee en voz alta, por largo que sea.</p></body></html>")
            put("OEBPS/tiny.xhtml", "<html><body><p>Casi nada.</p></body></html>")
            put("OEBPS/s.xhtml", "<html><head><title>No se narra</title></head><body><h2>Segundo orden</h2><p>Este va primero porque manda el orden de lectura.</p></body></html>")
            put(
                "OEBPS/text/a b.xhtml",
                "<html><body><h1>Capítulo uno</h1><div>Alerta de Persuasión</div><p>Una frase con <i>cursiva</i> , y sigue.</p><script>no()</script></body></html>",
            )
        }
        return f
    }

    @Test
    fun epubInReadingOrder() {
        val f = epub()
        val b = Epub.read(f)
        f.delete()
        assertEquals("Título del libro", b.title)
        assertEquals("es-ES", b.language)
        assertEquals(listOf("Segundo orden", "Capítulo uno"), b.chapters.map { it.title })
        assertEquals("Este va primero porque manda el orden de lectura.", b.chapters[0].text)
        // A heading with no full stop gets one, so it isn't read in one breath with what follows.
        assertEquals("Alerta de Persuasión.\nUna frase con cursiva, y sigue.", b.chapters[1].text)
    }

    @Test
    fun aPieceWithoutTimesSharesItsTimeByLength() {
        val s = Timing.segments(Transcript("Uno dos tres. Cuatro cinco seis siete ocho nueve.", emptyList()), 30.0)
        assertEquals(2, s.size)
        assertEquals(0.0, s[0].start, 0.0)
        assertEquals(s[0].end, s[1].start, 0.0)
        assertEquals(30.0, s[1].end, 0.001)
        assertTrue(s[1].end - s[1].start > s[0].end - s[0].start)
    }

    @Test
    fun aPieceWithTimesKeepsThem() {
        val given = listOf(Segment(1.0, 2.0, "x"))
        assertEquals(given, Timing.segments(Transcript("x", given), 30.0))
    }

    /** What the voz-server really gave: 23 s in one piece, with several sentences inside. */
    @Test
    fun aLongSegmentIsSharedBetweenItsSentences() {
        val long = Segment(10.0, 33.0, "La noche de difuntos me despertó. Su tañido monótono me trajo esta tradición. Intenté dormir de nuevo.")
        val short = Segment(33.0, 36.0, "Y otra cosa. Más.")
        val s = Timing.segments(Transcript("", listOf(long, short)), 60.0)
        assertEquals(4, s.size)
        assertEquals(10.0, s[0].start, 0.0)
        assertEquals(33.0, s[2].end, 0.001)
        assertEquals("Su tañido monótono me trajo esta tradición.", s[1].text)
        assertEquals(short, s[3])                    // what was short enough stays as it came
    }

    private fun words(vararg w: Pair<String, Double>): List<Word> = w.mapIndexed { i, (text, start) ->
        Word(start, w.getOrNull(i + 1)?.second ?: (start + 0.3), text)
    }

    /** The slow passage is what a share by length gets wrong: the second sentence starts at 15 s, not at 8. */
    @Test
    fun aSentenceStartsWithItsFirstWord() {
        val text = "Uno dos tres cuatro. Cinco seis siete ocho."
        val s = Timing.segments(
            Transcript(text, listOf(Segment(0.0, 20.0, text)),
                words("Uno" to 1.0, "dos" to 5.0, "tres" to 10.0, "cuatro." to 12.0, "Cinco" to 15.0, "seis" to 16.0, "siete" to 17.0, "ocho." to 18.0)),
            20.0,
        )
        assertEquals(listOf("Uno dos tres cuatro.", "Cinco seis siete ocho."), s.map { it.text })
        assertEquals(1.0, s[0].start, 0.0)
        assertEquals(15.0, s[1].start, 0.0)
        assertEquals(18.3, s[1].end, 0.001)
    }

    /** OpenAI's words come without punctuation; its segments have it. */
    @Test
    fun wordsWithoutPunctuationLineUpJustTheSame() {
        val text = "¿Uno, dos? Tres."
        val s = Timing.segments(Transcript(text, listOf(Segment(0.0, 9.0, text)), words("Uno" to 1.0, "dos" to 2.0, "Tres" to 7.0)), 9.0)
        assertEquals(listOf(1.0, 7.0), s.map { it.start })
    }

    /** What the voz-server really gave: a segment ending in the middle of a sentence, its last word beyond it. */
    @Test
    fun aSentenceCanCrossFromOneSegmentToTheNext() {
        val a = Segment(0.0, 5.0, "Uno dos. Tres cuatro")
        val b = Segment(5.0, 10.0, "cinco seis. Siete.")
        val s = Timing.segments(
            Transcript("", listOf(a, b), words("Uno" to 1.0, "dos." to 2.0, "Tres" to 3.0, "cuatro" to 4.9, "cinco" to 6.0, "seis." to 7.0, "Siete." to 9.0)),
            10.0,
        )
        assertEquals(listOf("Uno dos.", "Tres cuatro cinco seis.", "Siete."), s.map { it.text })
        assertEquals(listOf(1.0, 3.0, 9.0), s.map { it.start })
    }

    /** A word or two of difference between text and words: not enough to throw the times away. */
    @Test
    fun aSmallDifferenceDoesNotLoseTheLastSentences() {
        val text = "Una frase bastante larga para empezar. Otra frase bastante larga para seguir. Fin del todo aquí."
        val s = Timing.segments(
            Transcript(text, emptyList(), words("Una" to 1.0, "frase" to 2.0, "bastante" to 3.0, "larga" to 4.0, "para" to 5.0, "empezar." to 6.0,
                "Otra" to 10.0, "frase" to 11.0, "bastante" to 12.0, "larga" to 13.0, "para" to 14.0, "seguir." to 15.0, "Fin" to 20.0, "del" to 21.0, "todo" to 22.0)),
            30.0,
        )
        assertEquals(3, s.size)
        assertEquals(listOf(1.0, 10.0, 20.0), s.map { it.start })
    }

    @Test
    fun wordsLeftOverStretchTheLastSentence() {
        val s = Timing.segments(Transcript("Uno dos tres cuatro cinco.", emptyList(), words("Uno" to 1.0, "dos" to 2.0, "tres" to 3.0, "cuatro" to 4.0, "cinco." to 5.0, "ya" to 6.0)), 9.0)
        assertEquals(1, s.size)
        assertEquals(6.3, s[0].end, 0.001)
    }

    @Test
    fun wordsWithoutSegmentsAreEnough() {
        val s = Timing.segments(Transcript("Uno dos. Tres.", emptyList(), words("Uno" to 3.0, "dos." to 4.0, "Tres." to 8.0)), 30.0)
        assertEquals(listOf(3.0, 8.0), s.map { it.start })
    }

    @Test
    fun wordsThatAreNotThoseOfTheTextAreIgnored() {
        val text = "Una frase bastante larga con muchas palabras. Y otra frase igual de larga que la anterior."
        val s = Timing.segments(Transcript(text, listOf(Segment(0.0, 30.0, text)), words("Otra" to 1.0, "cosa." to 2.0)), 30.0)
        assertEquals(2, s.size)
        assertEquals(0.0, s[0].start, 0.0)                     // back to the share by length
        assertEquals(s[0].end, s[1].start, 0.0)
    }

    @Test
    fun silenceHasNoSegments() {
        assertTrue(Timing.segments(Transcript("", emptyList()), 30.0).isEmpty())
    }

    @Test
    fun chunksCutAtTheQuietestPlaceAndLoseNothing() {
        val rate = 1000
        val input = ShortArray(25_000) { 1000 }
        // A silence near the end of the first 10 s.
        for (i in 8_000 until 8_400) input[i] = 0
        val out = ArrayList<Int>()
        var total = 0
        val c = Chunker(rate, 10)
        val emit = { _: ShortArray, n: Int ->
            out += n
            total += n
        }
        var i = 0
        while (i < input.size) {                  // in pieces of odd sizes, like a decoder
            val n = minOf(3_333, input.size - i)
            c.add(input.copyOfRange(i, i + n), emit)
            i += n
        }
        c.finish(emit)
        assertEquals(input.size, total)
        assertTrue("cut at ${out[0]}", out[0] in 8_000..8_400)
    }

    @Test
    fun longTextIsCutUnderTheLimit() {
        val long = (1..300).joinToString(" ") { "Esta es la frase número $it." }
        val parts = Translation.splitLong(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 1400 })
        assertEquals(long, parts.joinToString(" "))
        assertEquals(listOf("corto"), Translation.splitLong("corto"))
    }

    @Test
    fun brokenAnswers() {
        assertTrue(Translation.looksBroken("hola", ""))
        assertTrue(Translation.looksBroken("hola", "x".repeat(100)))
        assertTrue(Translation.looksBroken("una frase cualquiera de prueba", "y luego y luego y luego y luego y luego y luego "))
        assertFalse(Translation.looksBroken("Good morning.", "Buenos días."))
    }

    /** What TranslateGemma 4B really answered to the single word "Name". */
    @Test
    fun anAnswerThatIsItsOwnInstructionsNeverReachesTheDocument() {
        val echo = "Eres un traductor profesional de inglés (en) a español (es-ES). Tu objetivo es transmitir con precisión " +
            "el significado y los matices del texto original en inglés. Por favor, traduzca el siguiente texto: Name"
        assertTrue(Translation.looksBroken("Name", echo))
        assertEquals("Name", Translation.settle("Name", echo))
        assertEquals("Nombre", Translation.settle("Name", "Nombre"))
        assertEquals("Name", Translation.settle("Name", ""))
    }

    private fun temp(ext: String, text: String): File =
        File.createTempFile("doc", ".$ext").apply { writeText(text) }

    private fun translated(f: File, map: (String) -> String = { "<$it>" }): String {
        val d = Docs.open(f, "es")
        d.units.forEach { it.apply(map(it.text)) }
        val out = File.createTempFile("out", ".$${f.extension}")
        d.save(out)
        return out.readText().also {
            out.delete()
            f.delete()
        }
    }

    @Test
    fun plainText() {
        val out = translated(temp("txt", "Primero.\n\n  Segundo.\n\n123\n"))
        assertEquals("<Primero.>\n\n  <Segundo.>\n\n123\n", out)
    }

    @Test
    fun markdownKeepsItsMarks() {
        val src = "# Title\n\n- one\n- two\n\n```\ncode here\n```\n\n| a | b |\n|---|---|\n| c | 1 |\n"
        val out = translated(temp("md", src))
        assertEquals("# <Title>\n\n- <one>\n- <two>\n\n```\ncode here\n```\n\n| <a> | <b> |\n|---|---|\n| <c> | 1 |\n", out)
    }

    @Test
    fun subtitlesKeepTheirTimes() {
        val src = "1\n00:00:01,000 --> 00:00:02,000\n<i>Hello there</i>\n\n2\n00:00:03,000 --> 00:00:04,000\nSecond\nline\n"
        val out = translated(temp("srt", src))
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n<i><Hello there></i>\n\n2\n00:00:03,000 --> 00:00:04,000\n<Second\nline>\n", out)
    }

    private fun zip(ext: String, vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("doc", ".$ext")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, s) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(s.toByteArray())
                z.closeEntry()
            }
        }
        return f
    }

    private fun readZip(f: File, name: String): String = ZipFile(f).use { z ->
        String(z.getInputStream(z.getEntry(name)).readBytes(), Charsets.UTF_8)
    }

    private fun saveZip(f: File): File {
        val d = Docs.open(f, "es")
        d.units.forEach { it.apply("T[${it.text}]") }
        val out = File.createTempFile("out", ".${f.extension}")
        d.save(out)
        return out
    }

    @Test
    fun wordKeepsTheStructureOfTheFirstRun() {
        val xml = """<w:document xmlns:w="w"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hel</w:t></w:r><w:r><w:t>lo</w:t></w:r></w:p><w:p><w:r><w:t>123</w:t></w:r></w:p></w:body></w:document>"""
        val out = saveZip(zip("docx", "word/document.xml" to xml, "word/styles.xml" to "<s/>"))
        val d = readZip(out, "word/document.xml")
        assertTrue(d, d.contains("T[Hello]"))
        assertTrue(d, d.contains("<w:b"))
        assertTrue(d, d.contains(">123<"))         // nothing to translate there
        assertEquals("<s/>", readZip(out, "word/styles.xml"))
    }

    @Test
    fun excelOnlyTouchesSharedStrings() {
        val strings = """<sst><si><t>Name</t></si><si><r><t>Ri</t></r><r><t>ch</t></r></si><si><t>42</t></si></sst>"""
        val sheet = """<worksheet><f>SUM(A1)</f></worksheet>"""
        val out = saveZip(zip("xlsx", "xl/sharedStrings.xml" to strings, "xl/worksheets/sheet1.xml" to sheet))
        val s = readZip(out, "xl/sharedStrings.xml")
        assertTrue(s, s.contains("T[Name]"))
        assertTrue(s, s.contains("T[Rich]"))
        assertTrue(s, s.contains(">42<"))
        assertEquals(sheet, readZip(out, "xl/worksheets/sheet1.xml"))
    }

    /** How openpyxl writes it: text in the cell itself, not in a shared table. */
    @Test
    fun excelWithTextInsideTheCells() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Name</t></is></c><c r="B1"><v>3</v></c><c r="B2"><f>SUM(B1:B1)</f><v>3</v></c></row></sheetData></worksheet>"""
        val out = saveZip(zip("xlsx", "xl/worksheets/sheet1.xml" to sheet, "xl/styles.xml" to "<s/>"))
        val s = readZip(out, "xl/worksheets/sheet1.xml")
        assertTrue(s, s.contains("T[Name]"))
        assertTrue(s, s.contains("<f>SUM(B1:B1)</f>"))
        assertTrue(s, s.contains("<v>3</v>"))
    }

    /** With nothing to translate it must say so, not blame the service. */
    @Test
    fun aDocumentWithoutTextHasNoUnits() {
        val f = zip("xlsx", "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row><c><v>3</v></c></row></sheetData></worksheet>")
        assertTrue(Docs.open(f, "es").units.isEmpty())
        f.delete()
    }

    @Test
    fun epubTranslationKeepsImagesAndNotesAndIsAValidZip() {
        val body = """<html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>Tit</title></head><body><p><img src="a.png"/>Some text<sup>1</sup></p><div><p>Inner one</p></div><p><i>All cursive</i></p><pre>keep code</pre></body></html>"""
        val out = saveZip(
            zip(
                "epub",
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""",
                "c.opf" to """<package xmlns:dc="d"><metadata><dc:title>Book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest></package>""",
                "a.xhtml" to body,
            ),
        )
        ZipFile(out).use { z ->
            val first = z.entries().nextElement()
            assertEquals("mimetype", first.name)            // EPUB readers check this
            assertEquals(ZipEntry.STORED, first.method)
        }
        val x = readZip(out, "a.xhtml")
        assertTrue(x, x.contains("<img src=\"a.png\""))
        assertTrue(x, x.contains("T[Some text]"))
        assertTrue(x, x.contains("<sup>1</sup>"))
        assertTrue(x, x.contains("T[Inner one]"))
        assertTrue(x, x.contains("<i>T[All cursive]</i>"))
        assertTrue(x, x.contains("<pre>keep code</pre>"))
        assertTrue(x, x.contains("lang=\"es\""))
        val opf = readZip(out, "c.opf")
        assertTrue(opf, opf.contains("T[Book]"))
        assertTrue(opf, opf.contains(">es<"))
    }

    private fun epubWith(body: String) = zip(
        "epub",
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""",
        "c.opf" to """<package xmlns:dc="d"><metadata><dc:title>Book</dc:title></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest></package>""",
        "a.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><body>$body</body></html>""",
    )

    /** Inline code and link targets inside a translated paragraph were lost (desktop fault 14). */
    @Test
    fun epubTranslationKeepsInlineCodeInPlaceAndLinkTargets() {
        val f = epubWith("""<p id="p1">Usa la orden <code>ls -la</code> y <a id="ancla"></a>sigue <span id="s2">aquí</span>.</p>""")
        val d = Docs.open(f, "en")
        assertEquals(listOf("Usa la orden ⟦0⟧ y sigue aquí."), d.units.filter { "⟦" in it.text }.map { it.text })
        d.units.forEach { u -> u.apply(if ("⟦" in u.text) "Use the command ⟦0⟧ and go on here." else u.text) }
        val out = File.createTempFile("out", ".epub")
        d.save(out)
        val x = readZip(out, "a.xhtml")
        assertTrue(x, x.contains("Use the command <code>ls -la</code> and go on here."))
        assertTrue(x, x.contains("id=\"ancla\""))
        assertTrue(x, x.contains("id=\"s2\""))
        assertTrue(x, x.contains("id=\"p1\""))
        assertTrue(Translation.prompt("Usa ⟦0⟧.", "es", "en").contains("Keep every marker"))
        assertFalse(Translation.prompt("Hola.", "es", "en").contains("⟦"))
    }

    @Test
    fun epubCodeWhoseMarkerTheModelDroppedGoesAtTheEnd() {
        val d = Docs.open(epubWith("""<p>Usa la orden <code>ls</code> ahora.</p>"""), "en")
        d.units.forEach { u -> u.apply(if ("⟦" in u.text) "Use the command now." else u.text) }
        val out = File.createTempFile("out", ".epub")
        d.save(out)
        assertTrue(readZip(out, "a.xhtml").contains("Use the command now. <code>ls</code></p>"))
    }

    /**
     * The JVM accepts inline flags like (?U) and Android's regex engine (ICU) rejects them, in the initialiser of
     * the object: every narration crashed on the phone with all these tests passing.
     */
    @Test
    fun noRegexFlagThatOnlyTheJvmAccepts() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.size > 10)
        for (f in sources) assertFalse(f.path, f.readText().contains("(?U)"))
    }

    /** Measured with the numbers written out, like the desktop: an ISBN no longer takes a piece past XTTS's limit. */
    @Test
    fun chunksWithLongNumbersLikeTheDesktopEngine() {
        val spell = golden.getJSONObject("spell").getJSONObject("es")
        val speller = { n: Long -> spell.optString(n.toString()).ifEmpty { n.toString() } }
        val close = mapOf("coma" to TextPrep.Close.CLAUSE, "frase" to TextPrep.Close.SENTENCE, "parrafo" to TextPrep.Close.PARAGRAPH)
        val cases = golden.getJSONArray("chunks_spoken")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val want = c.getJSONArray("out").let { a ->
                (0 until a.length()).map { k -> TextPrep.Chunk(a.getJSONArray(k).getString(0), close.getValue(a.getJSONArray(k).getString(1))) }
            }
            val got = TextPrep.splitChunks(c.getString("in"), "es", speller)
            assertEquals("case $i", want, got)
            assertTrue(got.size > 1)
            for (p in got) assertTrue(p.text, TextPrep.spokenLength(p.text, "es", speller) <= 220)
        }
        assertTrue(cases.length() >= 2)
    }

    @Test
    fun forVoiceLeavesSubtitlesAloneAndSpellsWhatItSends() {
        val spell = { n: Long -> "<$n>" }
        assertEquals("tengo <3> gatos", TextPrep.forVoice("Tengo 3 gatos.".removeSuffix("."), "es", spell))
        assertEquals("el doctor llegó a las <10> <30>.", TextPrep.forVoice("El Dr. llegó a las 10:30.", "es", spell))
        assertEquals("costó <1000> euros", TextPrep.forVoice("Costó 1.000 euros", "es", spell))
        assertEquals("vale <3> coma <5>", TextPrep.forVoice("Vale 3,5", "es", spell))
        assertEquals("ce pe u del Prefacio", TextPrep.forVoice("CPU del PREFACIO", "es", spell))
        assertEquals("estados Unidos, hoy", TextPrep.forVoice("EE. UU.: hoy", "es", spell))
        // In another language, no Spanish in the way.
        assertEquals("it cost <3> point <5>", TextPrep.forVoice("It cost 3.5", "en", spell))
        assertEquals("CPU rules", TextPrep.forVoice("CPU rules", "en", spell))
    }
}
