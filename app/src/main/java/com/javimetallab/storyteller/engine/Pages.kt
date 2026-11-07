package com.javimetallab.storyteller.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The page the listening is on. A port of st_pages.py of the desktop app: same rules, same
 * sample book in the tests, same numbers.
 *
 * The page is the printed book's when the EPUB carries it (EPUB3 page-list, EPUB2 NCX pageList or
 * epub:type="pagebreak" / role="doc-pagebreak" marks). Otherwise fixed pages of PAGE_LETTERS letters
 * and digits counted from the start of the book, shown as approximate. Sentences are placed in the
 * EPUB by their letters and digits only, so how the text was cut into sentences doesn't matter.
 */
object Pages {
    const val PAGE_LETTERS = 1500
    private const val KEY_LETTERS = 40
    private val SKIP = setOf("head", "script", "style")      // a <title> outside <head> (SVG) is read aloud
    private val HEADS = setOf("h1", "h2", "h3")
    private val SPACES = Regex("\\s+")

    /** Only letters (any script) and decimal digits. A letter outside the BMP counts as one, as in Python. */
    fun letters(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp) || Character.isDigit(cp)) {
                if (Character.charCount(cp) == 1) sb.append(cp.toChar()) else sb.append('�')
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** "23", "xii"; "Page 23" → "23". */
    fun label(text: String): String {
        val s = text.replace(SPACES, " ").trim()
        if (s.length <= 8) return s
        return Regex("\\d+").findAll(s).lastOrNull()?.value ?: s.take(8)
    }

    private class Stream {
        val sb = StringBuilder()
        val ids = HashMap<String, Int>()
        val breaks = ArrayList<Triple<Int, String, String>>()     // position, label, id
        var titleDone = false
    }

    /** Letters of a document, where each id is and its page marks. Like chapters_from_epub: no head, scripts
     *  or styles, and not the first h1/h2/h3, the chapter's title, which isn't in its text. */
    private fun stream(html: String): Stream {
        val s = Stream()
        val doc = Jsoup.parse(html, "", Parser.xmlParser())
        fun visit(node: Node, inTitle: Boolean) {
            when (node) {
                is TextNode -> if (!inTitle) s.sb.append(letters(node.wholeText))
                is Element -> {
                    val tag = node.normalName().substringAfter(':')
                    if (tag in SKIP) return
                    val id = node.attr("id")
                    if (id.isNotEmpty()) s.ids.putIfAbsent(id, s.sb.length)
                    val kinds = (node.attr("epub:type") + " " + node.attr("role")).split(SPACES)
                    if ("pagebreak" in kinds || "doc-pagebreak" in kinds) {
                        val own = node.attr("title").ifBlank { node.attr("aria-label") }.trim()
                        s.breaks += Triple(s.sb.length, own.ifEmpty { label(node.text()) }, id)
                    }
                    var title = inTitle
                    if (!s.titleDone && tag in HEADS) {
                        s.titleDone = true
                        title = true
                    }
                    for (c in node.childNodes()) visit(c, title)
                }
                else -> Unit
            }
        }
        visit(doc, false)
        return s
    }

    private fun decode(href: String) = try {
        URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        href
    }

    /** Path inside the zip and fragment of a link written in baseFile. */
    private fun resolve(baseFile: String, href: String): Pair<String, String> {
        val h = decode(href)
        val path = h.substringBefore('#')
        val frag = if ('#' in h) h.substringAfter('#') else ""
        if (path.isEmpty()) return baseFile to frag
        val dir = if ('/' in baseFile) baseFile.substringBeforeLast('/') + "/" else ""
        return Epub.normalize(dir + path) to frag
    }

    fun fromEpub(file: File, perPage: Int = PAGE_LETTERS): BookPages? = try {
        ZipFile(file).use { zip ->
            fun read(name: String): String? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }
            val container = Jsoup.parse(read("META-INF/container.xml") ?: return null, "", Parser.xmlParser())
            val opfPath = container.getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: return null
            val opf = Jsoup.parse(read(opfPath) ?: return null, "", Parser.xmlParser())
            val base = if ('/' in opfPath) opfPath.substringBeforeLast('/') + "/" else ""
            data class Item(val href: String, val full: String, val type: String, val props: String)
            val items = LinkedHashMap<String, Item>()
            for (e in opf.getElementsByTag("item")) {
                val href = decode(e.attr("href"))
                items[e.attr("id")] = Item(href, Epub.normalize(base + href), e.attr("media-type"), e.attr("properties"))
            }
            val tocId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc").orEmpty()
            val nav = items.values.firstOrNull { "nav" in it.props.split(SPACES) }?.full
            val ncx = items.entries.firstOrNull { it.value.type == "application/x-dtbncx+xml" || it.key == tocId }?.value?.full
            val order = opf.getElementsByTag("itemref").mapNotNull { items[it.attr("idref")] }.ifEmpty { items.values.toList() }

            val streams = HashMap<String, Stream?>()
            val startOf = HashMap<String, Int>()
            val docs = ArrayList<Pair<Int, String>>()
            val inline = ArrayList<Pair<Pair<String, String>, Pair<Int, String>>>()   // (file, id) → (position, label)
            var offset = 0
            for (it in order) {
                val h = it.href.lowercase()
                val isDoc = it.type == "application/xhtml+xml" || it.type == "text/html" ||
                    h.endsWith(".xhtml") || h.endsWith(".html") || h.endsWith(".htm")
                if (!isDoc || it.full in startOf) continue
                val html = read(it.full) ?: continue
                startOf[it.full] = offset
                if ("nav" in h || "toc" in h) {               // the index isn't read: its marks fall where the next starts
                    streams[it.full] = null
                    continue
                }
                val s = stream(html)
                streams[it.full] = s
                val txt = s.sb.toString()
                if (txt.isNotEmpty()) docs += offset to txt
                for ((pos, lab, id) in s.breaks) inline += (it.full to id) to (offset + pos to lab)
                offset += txt.length
            }

            fun where(full: String, frag: String): Int? {
                val start = startOf[full] ?: return null
                val s = streams[full]
                if (s == null || frag.isEmpty()) return start
                return s.ids[frag]?.let { start + it }
            }

            val targets = ArrayList<Pair<Pair<String, String>, String>>()
            if (nav != null) {
                read(nav)?.let { html ->
                    val d = Jsoup.parse(html, "", Parser.xmlParser())
                    for (n in d.getElementsByTag("nav")) {
                        val kinds = (n.attr("epub:type") + " " + n.attr("role")).split(SPACES)
                        if ("page-list" !in kinds && "doc-pagelist" !in kinds) continue
                        for (a in n.getElementsByTag("a")) targets += resolve(nav, a.attr("href")) to label(a.text())
                    }
                }
            }
            if (targets.isEmpty() && ncx != null) {
                read(ncx)?.let { xml ->
                    val d = Jsoup.parse(xml, "", Parser.xmlParser())
                    for (pt in d.getElementsByTag("pageTarget")) {
                        val lab = pt.getElementsByTag("text").firstOrNull()?.text().orEmpty().ifEmpty { pt.attr("value") }
                        val src = pt.getElementsByTag("content").firstOrNull()?.attr("src") ?: continue
                        targets += resolve(ncx, src) to label(lab)
                    }
                }
            }
            val marks = ArrayList<Pair<Int, String>>()
            if (targets.isNotEmpty()) {
                val own = inline.filter { it.first.second.isNotEmpty() }.associate { it.first to it.second.second }
                for ((where2, lab0) in targets) {
                    val p = where(where2.first, where2.second) ?: continue
                    val lab = lab0.ifEmpty { own[where2].orEmpty() }
                    if (lab.isNotEmpty()) marks += p to lab
                }
            } else {
                for ((_, v) in inline) if (v.second.isNotEmpty()) marks += v
            }
            BookPages(marks.isNotEmpty(), offset, marks, docs, perPage)
        }
    } catch (_: Exception) {
        null
    }

    /** No EPUB (a transcribed audiobook): approximate pages with the text of the subtitles. */
    fun fromCues(chapters: List<List<String>>, perPage: Int = PAGE_LETTERS): BookPages {
        val docs = ArrayList<Pair<Int, String>>()
        var offset = 0
        for (texts in chapters) {
            val txt = texts.joinToString("") { letters(it) }
            docs += offset to txt
            offset += txt.length
        }
        return BookPages(false, offset, emptyList(), docs, perPage)
    }

    /** The book in that folder: with its source.epub if it has one, else with its subtitles. */
    fun forBook(dir: File, chapterCues: () -> List<List<String>>): BookPages {
        val epub = File(dir, "source.epub")
        if (epub.exists()) fromEpub(epub)?.let { if (it.totalLetters > 0) return it }
        return fromCues(chapterCues())
    }

    internal const val KEY = KEY_LETTERS
}

/** Pages of a book. Offsets: letters from the start of the book. */
class BookPages(
    val real: Boolean,
    totalLetters: Int,
    markers: List<Pair<Int, String>>,
    private val docs: List<Pair<Int, String>>,
    private val perPage: Int = Pages.PAGE_LETTERS,
) {
    val totalLetters = totalLetters.coerceAtLeast(0)
    private val marks = markers.sortedBy { it.first }
    val total: Int = if (real) {
        marks.mapNotNull { m -> m.second.takeIf { s -> s.all(Char::isDigit) }?.toIntOrNull() }.maxOrNull() ?: marks.size
    } else {
        ceil(this.totalLetters.toDouble() / perPage).toInt().coerceAtLeast(1)
    }

    fun page(offset: Int): String {
        if (!real) return minOf(total, offset.coerceAtLeast(0) / perPage + 1).toString()
        var lo = 0
        var hi = marks.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (marks[mid].first <= offset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return marks[found].second
    }

    fun percent(offset: Int): Int =
        if (totalLetters == 0) 0 else (offset.toLong() * 100 / totalLetters).toInt().coerceIn(0, 100)

    /** Where in the book each sentence of a chapter starts, or null if it isn't there. chapId: the section number. */
    fun chapterOffsets(chapId: Int, texts: List<String>): List<Int>? {
        if (docs.isEmpty()) return null
        val keys = texts.map(Pages::letters)
        // Chapter n is the n-th document with text; with very short sections the engine skips it can be a
        // little further on: the closest document that holds it wins.
        val hint = chapId - 1
        fun locate(key: String): Pair<Int, Int>? {
            var best: Pair<Int, Int>? = null
            for ((k, d) in docs.withIndex()) {
                val p = d.second.indexOf(key)
                if (p >= 0 && (best == null || abs(k - hint) < abs(best.first - hint))) best = k to p
            }
            return best
        }
        val key = keys.joinToString("").take(Pages.KEY)
        var best = if (key.isNotEmpty()) locate(key) else null
        var before = 0
        for (k in keys.take(8)) {                          // the start doesn't match: by one of the first sentences
            if (best != null) break
            if (k.length >= 10) locate(k.take(Pages.KEY))?.let { (d, p) -> best = d to (p - before).coerceAtLeast(0) }
            before += k.length
        }
        val (docIndex, at) = best ?: return null
        val (start, txt) = docs[docIndex]
        var cur = at
        var lost = 0
        val out = ArrayList<Int>(keys.size)
        for (k in keys) {
            // As Python's find(k, cur, end): it has to end within the window.
            val end = minOf(txt.length, cur + lost + k.length + SLACK)
            val p = if (k.isEmpty() || cur >= end) -1 else txt.substring(cur, end).indexOf(k).let { if (it < 0) -1 else cur + it }
            if (p < 0) {
                out += start + cur + lost
                lost += k.length
            } else {
                out += start + p
                cur = p + k.length
                lost = 0
            }
        }
        return out
    }

    private companion object {
        const val SLACK = 400
    }
}
