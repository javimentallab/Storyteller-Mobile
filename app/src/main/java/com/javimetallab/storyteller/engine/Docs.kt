package com.javimetallab.storyteller.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A piece of translatable text, and how to put its translation back. */
class DocUnit(val text: String, val apply: (String) -> Unit)

interface Doc {
    val units: List<DocUnit>
    fun save(out: File)
}

/** Prompt and splitting. A port of engine/cc_translate.py of the desktop app. */
object Translation {
    private val NAMES = mapOf(
        "es" to ("Spanish" to "es-ES"), "en" to ("English" to "en"), "de" to ("German" to "de"),
        "fr" to ("French" to "fr"), "it" to ("Italian" to "it"), "pt" to ("Portuguese" to "pt"),
        "nl" to ("Dutch" to "nl"), "pl" to ("Polish" to "pl"), "ru" to ("Russian" to "ru"),
        "uk" to ("Ukrainian" to "uk"), "ja" to ("Japanese" to "ja"), "ko" to ("Korean" to "ko"),
        "zh" to ("Chinese" to "zh-Hans"), "hi" to ("Hindi" to "hi"), "th" to ("Thai" to "th"),
        "ca" to ("Catalan" to "ca"),
    )
    val LANGUAGES = NAMES.keys.toList()
    private const val MAX_CHARS = 1400
    private val LETTER = Regex("\\p{L}")
    private val SENTENCE = Regex("(?<=[.!?;:…。！？])\\s+")
    private val LOOP = Regex("(.{6,40}?)\\1{4,}")

    /** It works with TranslateGemma as it is, and any general model understands it. */
    fun prompt(text: String, src: String, tgt: String): String {
        val (sl, sc) = NAMES[src] ?: (src to src)
        val (tl, tc) = NAMES[tgt] ?: (tgt to tgt)
        return "You are a professional $sl ($sc) to $tl ($tc) translator. Your goal is to accurately " +
            "convey the meaning and nuances of the original $sl text while adhering to $tl grammar, " +
            "vocabulary, and cultural sensitivities.\n" +
            "Produce only the $tl translation, without any additional explanations or commentary. " +
            "Please translate the following $sl text into $tl:\n\n\n$text"
    }

    fun needsTranslation(t: String) = LETTER.containsMatchIn(t)

    fun splitLong(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val out = ArrayList<String>()
        var cur = ""
        for (sentence in SENTENCE.split(text)) {
            var s = sentence
            while (s.length > MAX_CHARS) {
                var cut = s.lastIndexOf(", ", MAX_CHARS - 1)
                cut = if (cut > MAX_CHARS / 3) cut + 1 else MAX_CHARS
                if (cur.isNotEmpty()) {
                    out += cur
                    cur = ""
                }
                out += s.substring(0, cut).trim()
                s = s.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 1 + s.length > MAX_CHARS) {
                out += cur
                cur = s
            } else {
                cur = "$cur $s".trim()
            }
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** Empty, far too long, or stuck repeating itself. */
    fun looksBroken(src: String, out: String): Boolean {
        if (out.isBlank()) return true
        if (out.length > 3 * src.length + 80) return true
        return LOOP.containsMatchIn(out) && !LOOP.containsMatchIn(src)
    }

    /**
     * What stays after the second try. If it is still broken, the original: a small model handed a
     * single word can answer with its own instructions, and that must not end up in the document.
     */
    fun settle(source: String, retry: String): String = if (looksBroken(source, retry)) source else retry

    fun key(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

object Docs {
    val EXTENSIONS = setOf("epub", "pdf", "docx", "pptx", "xlsx", "srt", "vtt", "txt", "md")

    /** [title] and [onPage] are for a PDF: it has no title of its own to keep, and it can take long to read. */
    fun open(file: File, tgt: String, title: String = file.nameWithoutExtension, onPage: () -> Unit = {}): Doc = when (file.extension.lowercase()) {
        "epub" -> EpubDoc(file, tgt)
        "pdf" -> PdfDoc(file, tgt, title, onPage)
        "docx" -> OfficeDoc.docx(file)
        "pptx" -> OfficeDoc.pptx(file)
        "xlsx" -> OfficeDoc.xlsx(file)
        "srt", "vtt" -> SubsDoc(readText(file))
        "md" -> TextDoc(readText(file), markdown = true)
        "txt" -> TextDoc(readText(file), markdown = false)
        else -> throw IOException("type")
    }

    /** UTF-8 if it is, the usual Western encoding if not. */
    fun readText(file: File): String {
        val raw = file.readBytes()
        val utf8 = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(raw, charset("windows-1252"))
        }
        return utf8.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n")
    }

    fun ws(t: String) = t.replace(Regex("\\s*\\n\\s*"), "\n").replace(Regex("[ \\t\\u000C\\u00A0]+"), " ").trim()

    internal fun xml(data: ByteArray): Document {
        val d = Jsoup.parse(String(data, Charsets.UTF_8), "", Parser.xmlParser())
        d.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false)
            .escapeMode(Entities.EscapeMode.xhtml).charset(Charsets.UTF_8)
        return d
    }

    /** The same entries, in the same order, some of them changed. mimetype first and stored, as EPUB wants. */
    internal fun rewriteZip(names: List<String>, read: (String) -> ByteArray, out: File) {
        val tmp = File(out.path + ".tmp")
        ZipOutputStream(tmp.outputStream().buffered()).use { z ->
            for (name in names.sortedBy { if (it == "mimetype") 0 else 1 }) {
                val data = read(name)
                val e = ZipEntry(name)
                if (name == "mimetype") {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException("rename ${out.name}")
    }
}

// ---- EPUB
private class EpubDoc(file: File, private val tgt: String) : Doc {
    private companion object {
        val BLOCK = setOf(
            "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "td", "th", "dt", "dd", "caption", "figcaption", "blockquote",
            "div", "section", "article", "aside", "header", "footer", "title", "summary", "label", "legend", "option",
        )
        val SKIP = setOf("script", "style", "code", "pre", "math", "svg", "kbd", "samp", "var")
        val KEEP_INLINE = setOf("img", "image", "svg", "math", "object", "video", "audio", "iframe")
        val NOTE = Regex("^\\W*(\\d{1,3}|[*†‡§]+|[ivxlc]{1,5})\\W*$", RegexOption.IGNORE_CASE)
    }

    private val data = LinkedHashMap<String, ByteArray>()
    private val parsed = LinkedHashMap<String, Document>()
    private val opfName: String
    private val opf: Document
    override val units = ArrayList<DocUnit>()

    init {
        ZipFile(file).use { z ->
            for (e in z.entries()) if (!e.isDirectory) data[e.name] = z.getInputStream(e).use { it.readBytes() }
        }
        val container = Docs.xml(data["META-INF/container.xml"] ?: throw IOException("no container"))
        opfName = container.selectFirst("rootfile")?.attr("full-path") ?: throw IOException("no rootfile")
        val base = if ('/' in opfName) opfName.substringBeforeLast('/') + "/" else ""
        opf = Docs.xml(data[opfName] ?: throw IOException("no opf"))
        for (item in opf.select("item")) {
            val name = Epub.normalize(base + decode(item.attr("href")))
            if (name !in data) continue
            when (item.attr("media-type")) {
                "application/xhtml+xml", "text/html" -> loadXhtml(name)
                "application/x-dtbncx+xml" -> loadNcx(name)
            }
        }
        for (tag in opf.getAllElements()) {
            val n = tag.normalName().substringAfter(':')
            if (n in setOf("title", "description", "subject") && tag.children().isEmpty() &&
                Translation.needsTranslation(tag.text())
            ) units += DocUnit(Docs.ws(tag.text())) { tag.text(it) }
        }
    }

    private fun decode(h: String) = try {
        java.net.URLDecoder.decode(h.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        h
    }

    private fun lname(e: Element) = e.normalName().substringAfter(':')

    private fun loadXhtml(name: String) {
        val doc = Docs.xml(data[name]!!)
        parsed[name] = doc
        val html = doc.getAllElements().firstOrNull { lname(it) == "html" }
        html?.attributes()?.forEach { a -> if (a.key == "lang" || a.key == "xml:lang") a.setValue(tgt) }
        doc.getAllElements().firstOrNull { lname(it) == "title" }?.let { t ->
            if (Translation.needsTranslation(t.text())) units += DocUnit(Docs.ws(t.text())) { t.text(it) }
        }
        walk(doc.getAllElements().firstOrNull { lname(it) == "body" } ?: doc)
    }

    private fun loadNcx(name: String) {
        val doc = Docs.xml(data[name]!!)
        parsed[name] = doc
        for (t in doc.select("text")) {
            if (Translation.needsTranslation(t.text())) units += DocUnit(Docs.ws(t.text())) { s -> t.text(s) }
        }
    }

    private fun isLeaf(e: Element) = e.getAllElements().drop(1).none { lname(it) in BLOCK }

    private fun walk(el: Element) {
        for (child in el.childNodes().toList()) {
            if (child is TextNode) {
                val s = child.wholeText
                if (Translation.needsTranslation(s) && lname(el) !in SKIP) {
                    val lead = s.takeWhile { it.isWhitespace() }
                    val trail = s.takeLastWhile { it.isWhitespace() }
                    units += DocUnit(Docs.ws(s)) { t -> child.text(lead + t + trail) }
                }
            } else if (child is Element) {
                val n = lname(child)
                when {
                    n in SKIP -> Unit
                    isLeaf(child) -> addBlock(child)
                    else -> walk(child)
                }
            }
        }
    }

    private fun isNote(e: Element): Boolean {
        val n = lname(e)
        if (n !in setOf("a", "sup", "span", "small")) return false
        if (!NOTE.matches(e.text())) return false
        return n == "sup" || e.getAllElements().any { lname(it) == "sup" } ||
            (n == "a" && ("noteref" in e.attr("epub:type") || '#' in e.attr("href")))
    }

    private fun blockText(el: Element): String {
        val out = StringBuilder()
        fun go(e: Element) {
            for (c in e.childNodes()) {
                if (c is TextNode) {
                    out.append(c.wholeText)
                } else if (c is Element) {
                    val n = lname(c)
                    if (n == "br") out.append('\n') else if (n !in SKIP && !isNote(c)) go(c)
                }
            }
        }
        go(el)
        return Docs.ws(out.toString())
    }

    private fun addBlock(el: Element) {
        val text = blockText(el)
        if (Translation.needsTranslation(text)) units += DocUnit(text) { setBlock(el, it) }
    }

    /** Footnotes, images and formulas stay. Inline formatting inside a sentence is lost. */
    private fun setBlock(el: Element, t: String) {
        val front = ArrayList<Element>()
        val end = ArrayList<Element>()
        for (d in el.getAllElements().drop(1).toList()) {
            if (d.parent() == null) continue
            if (lname(d) in KEEP_INLINE && d.parents().none { lname(it) in KEEP_INLINE }) {
                d.remove()
                front.add(d)
            } else if (isNote(d) && d.parents().none { isNote(it) }) {
                d.remove()
                end.add(d)
            }
        }
        var target = el
        while (true) {       // a block wholly wrapped in one inline (<p><i>…</i></p>) keeps it
            val kids = target.childNodes().filter { it !is TextNode || it.wholeText.isNotBlank() }
            val only = kids.singleOrNull() as? Element ?: break
            if (lname(only) in BLOCK || lname(only) == "br") break
            target = only
        }
        target.empty()
        front.forEach { target.appendChild(it) }
        t.split("\n").forEachIndexed { i, line ->
            if (i > 0) target.appendElement("br")
            target.appendChild(TextNode(line))
        }
        end.forEach { target.appendChild(it) }
    }

    override fun save(out: File) {
        for (l in opf.getAllElements()) if (l.normalName().substringAfter(':') == "language") l.text(tgt)
        data[opfName] = opf.outerHtml().toByteArray(Charsets.UTF_8)
        for ((name, doc) in parsed) data[name] = doc.outerHtml().toByteArray(Charsets.UTF_8)
        Docs.rewriteZip(data.keys.toList(), { data[it]!! }, out)
    }
}

// ---- Word, PowerPoint, Excel: zips of XML, and the text is in plain sight
private class OfficeDoc(file: File, private val parts: List<String>, private val build: OfficeDoc.(Document) -> Unit) : Doc {
    companion object {
        fun docx(f: File) = OfficeDoc(
            f,
            listOf("word/document.xml", "word/footnotes.xml", "word/endnotes.xml", "word/header*.xml", "word/footer*.xml"),
        ) { d -> for (p in d.select("w|p")) addRuns(p, "w|r", "w|t") }

        fun pptx(f: File) = OfficeDoc(f, listOf("ppt/slides/slide*.xml", "ppt/notesSlides/notesSlide*.xml")) { d ->
            for (p in d.select("a|p")) addRuns(p, "a|r", "a|t")
        }

        /**
         * Excel keeps text in a shared table, or inside each cell (`is`) in files other programs
         * write. Formulas and numbers are elsewhere (`f`, `v`), so they are safe.
         */
        fun xlsx(f: File) = OfficeDoc(f, listOf("xl/sharedStrings.xml", "xl/worksheets/sheet*.xml")) { d ->
            for (s in d.select("si, is")) addRuns(s, "r", "t")
        }
    }

    private val data = LinkedHashMap<String, ByteArray>()
    private val parsed = LinkedHashMap<String, Document>()
    override val units = ArrayList<DocUnit>()

    init {
        ZipFile(file).use { z ->
            for (e in z.entries()) if (!e.isDirectory) data[e.name] = z.getInputStream(e).use { it.readBytes() }
        }
        val rxs = parts.map { Regex(Regex.escape(it).replace("*", "\\E\\d*\\Q")) }
        for (name in data.keys.toList()) {
            if (rxs.none { it.matches(name) }) continue
            val doc = Docs.xml(data[name]!!)
            parsed[name] = doc
            build(doc)
        }
    }

    /** The paragraph's text, whatever the runs it is scattered in. It goes back into the first one. */
    private fun addRuns(p: Element, run: String, textTag: String) {
        val runs = p.select(textTag).filter { t -> t.parents().firstOrNull { it.tagName() == p.tagName() } === p }
        if (runs.isEmpty()) return
        val text = Docs.ws(runs.joinToString("") { it.wholeText() })
        if (!Translation.needsTranslation(text)) return
        units += DocUnit(text) { t ->
            runs.forEachIndexed { i, r ->
                r.text(if (i == 0) t.replace("\n", " ") else "")
                if (i == 0) r.attr("xml:space", "preserve")
            }
        }
    }

    override fun save(out: File) {
        for ((name, doc) in parsed) data[name] = doc.outerHtml().toByteArray(Charsets.UTF_8)
        Docs.rewriteZip(data.keys.toList(), { data[it]!! }, out)
    }
}

// ---- SRT / VTT
private class SubsDoc(text: String) : Doc {
    private val blocks = text.trim('\n').split(Regex("\\n{2,}")).toMutableList()
    override val units = ArrayList<DocUnit>()

    init {
        val tag = Regex("</?[a-zA-Z][^>]*>")
        val ass = Regex("^(\\{\\\\[^}]*\\})+")
        val wholeItalic = Regex("\\s*<i>.*</i>\\s*", RegexOption.DOT_MATCHES_ALL)
        for ((i, b) in blocks.withIndex()) {
            val lines = b.split("\n")
            val ti = lines.indexOfFirst { "-->" in it }
            if (ti < 0 || ti == lines.size - 1) continue
            var joined = lines.drop(ti + 1).joinToString("\n")
            val prefix = ass.find(joined)?.value.orEmpty()
            joined = joined.removePrefix(prefix)
            val italic = wholeItalic.matches(joined) && Regex("<i>").findAll(joined).count() == 1
            val plain = Docs.ws(tag.replace(joined, ""))
            if (!Translation.needsTranslation(plain)) continue
            val head = lines.take(ti + 1).joinToString("\n")
            units += DocUnit(plain) { t ->
                val body = if (italic) "<i>${t.trim()}</i>" else t.trim()
                blocks[i] = head + "\n" + prefix + body
            }
        }
    }

    override fun save(out: File) = com.javimetallab.storyteller.data.Library.writeAtomic(out, blocks.joinToString("\n\n") + "\n")
}

// ---- TXT / MD
private class TextDoc(text: String, markdown: Boolean) : Doc {
    private val pieces = ArrayList<Array<String>>()     // one element each; translatable ones get replaced
    override val units = ArrayList<DocUnit>()
    private var inCode = false

    init {
        val mdPrefix = Regex("^(\\s*(?:#{1,6}\\s+|[-*+]\\s+(?:\\[[ xX]\\]\\s+)?|\\d+[.)]\\s+|>\\s*)+)")
        val gap = Regex("\\n\\s*\\n")
        var last = 0
        val chunks = ArrayList<String>()
        for (m in gap.findAll(text)) {
            chunks += text.substring(last, m.range.first)
            chunks += m.value
            last = m.range.last + 1
        }
        chunks += text.substring(last)
        for (chunk in chunks) {
            when {
                chunk.isBlank() -> fixed(chunk)
                markdown -> md(chunk, mdPrefix)
                else -> txt(chunk)
            }
        }
    }

    private fun fixed(s: String) {
        pieces += arrayOf(s)
    }

    private fun slot(text: String, lead: String = "", trail: String = "") {
        fixed(lead)
        val cell = arrayOf(text)
        pieces += cell
        fixed(trail)
        units += DocUnit(text) { cell[0] = it }
    }

    private fun txt(chunk: String) {
        val lines = chunk.split("\n")
        val lead = chunk.takeWhile { it.isWhitespace() }
        // prose wrapped by hand is one paragraph; anything else keeps its lines
        val text = if (lines.size >= 3 && lines.sumOf { it.length } / lines.size > 50) {
            lines.joinToString(" ") { it.trim() }
        } else {
            lines.joinToString("\n") { it.trim() }
        }
        if (Translation.needsTranslation(text)) slot(Docs.ws(text), lead) else fixed(chunk)
    }

    private fun md(chunk: String, prefix: Regex) {
        val lines = chunk.split("\n")
        val fence = { l: String -> l.trimStart().startsWith("```") || l.trimStart().startsWith("~~~") }
        if (inCode || fence(lines[0])) {
            fixed(chunk)
            if (lines.count(fence) % 2 == 1) inCode = !inCode
            return
        }
        if (lines.filter { it.isNotBlank() }.all { it.startsWith("    ") || it.startsWith("\t") }) {
            fixed(chunk)
            return
        }
        if (lines.filter { it.isNotBlank() }.all { it.trim().startsWith("|") }) {
            val rule = Regex("\\s*\\|?[\\s:|-]+\\|?\\s*")
            lines.forEachIndexed { k, l ->
                if (k > 0) fixed("\n")
                if (rule.matches(l)) {
                    fixed(l)
                } else {
                    l.split("|").forEachIndexed { j, cell ->
                        if (j > 0) fixed("|")
                        if (Translation.needsTranslation(cell)) {
                            slot(cell.trim(), cell.takeWhile { it.isWhitespace() }, cell.takeLastWhile { it.isWhitespace() })
                        } else {
                            fixed(cell)
                        }
                    }
                }
            }
            return
        }
        if (lines.any { prefix.find(it) != null }) {
            lines.forEachIndexed { k, l ->
                if (k > 0) fixed("\n")
                val pre = prefix.find(l)?.value ?: l.takeWhile { it.isWhitespace() }
                val body = l.substring(pre.length)
                if (Translation.needsTranslation(body)) slot(body.trim(), pre) else fixed(l)
            }
            return
        }
        txt(chunk)
    }

    override fun save(out: File) =
        com.javimetallab.storyteller.data.Library.writeAtomic(out, pieces.joinToString("") { it[0] })
}
