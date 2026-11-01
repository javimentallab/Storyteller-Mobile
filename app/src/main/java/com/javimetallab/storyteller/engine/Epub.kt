package com.javimetallab.storyteller.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class EpubChapter(val title: String, val text: String)

data class EpubBook(val title: String, val language: String, val chapters: List<EpubChapter>)

/** Reads an EPUB in reading order. A port of chapters_from_epub of the desktop app. */
object Epub {
    private const val SEP = '\u0001'
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "td", "th", "dt", "dd", "pre",
        "figcaption", "caption", "section", "aside", "header", "footer", "tr",
    )
    private const val END_PUNCT = ".?!…:;,»\"”’)]"
    private val HAS_TEXT = Regex("[A-Za-z\\u00C0-\\u024F0-9\\p{L}]")
    private val SPACE_BEFORE = Regex("\\s+([,.;:!?)\\]»”])")
    private val SPACE_AFTER = Regex("([(\\[«“¿¡])\\s+")
    private val MIN_CHARS = 25

    fun read(file: File): EpubBook {
        ZipFile(file).use { zip ->
            val container = xml(zip.read("META-INF/container.xml"))
            val opfPath = container.selectFirst("rootfile")?.attr("full-path")
                ?: throw java.io.IOException("no rootfile")
            val base = if ('/' in opfPath) opfPath.substringBeforeLast('/') + "/" else ""
            val opf = xml(zip.read(opfPath))
            val items = opf.select("manifest > item, item").associateBy { it.attr("id") }
            val order = opf.select("itemref").mapNotNull { items[it.attr("idref")] }
                .ifEmpty { items.values.toList() }
            val chapters = ArrayList<EpubChapter>()
            for (item in order) {
                val href = decode(item.attr("href"))
                if (!isDoc(item, href)) continue
                val lower = href.lowercase()
                if ("nav" in lower || "toc" in lower) continue     // the index isn't read aloud
                val data = zip.readOrNull(normalize(base + href)) ?: continue
                chapter(String(data, Charsets.UTF_8), chapters.size)?.let { chapters += it }
            }
            return EpubBook(
                title = opf.selectFirst("title, dc|title")?.text()?.trim().orEmpty(),
                language = opf.selectFirst("language, dc|language")?.text()?.trim().orEmpty(),
                chapters = chapters,
            )
        }
    }

    private fun chapter(html: String, position: Int): EpubChapter? {
        val doc = Jsoup.parse(html)
        doc.head().remove()                                       // its <title> isn't narrated
        doc.select("script, style").remove()
        val heading = doc.selectFirst("h1, h2, h3")
        val title = heading?.text()?.trim().orEmpty()
        heading?.remove()
        val text = blocksText(doc)
        if (text.length < MIN_CHARS) return null
        return EpubChapter(title.ifEmpty { "Capitulo ${position + 1}" }, text)
    }

    /**
     * Text by blocks: each paragraph, title or box on its line and, if it doesn't end in a mark,
     * with a full stop, so that a heading isn't read in one breath with the next sentence.
     */
    fun blocksText(root: Element): String {
        val sb = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode) {
                    sb.append(node.wholeText).append(' ')
                } else if (node is Element && (node.normalName() == "br" || node.normalName() in BLOCK_TAGS)) {
                    sb.append(SEP)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in BLOCK_TAGS) sb.append(SEP)
            }
        }, root)
        val lines = ArrayList<String>()
        for (piece in sb.toString().split(SEP)) {
            var ln = piece.replace(Regex("[\\s\\u00A0]+"), " ").trim()
            if (ln.isEmpty()) continue
            ln = SPACE_BEFORE.replace(ln, "$1")
            ln = SPACE_AFTER.replace(ln, "$1")
            if (HAS_TEXT.containsMatchIn(ln) && ln.last() !in END_PUNCT) ln += "."
            lines += ln
        }
        return lines.joinToString("\n")
    }

    private fun xml(data: ByteArray): Document =
        Jsoup.parse(String(data, Charsets.UTF_8), "", Parser.xmlParser())

    private fun isDoc(item: Element, href: String): Boolean {
        val type = item.attr("media-type")
        val h = href.lowercase()
        return type == "application/xhtml+xml" || type == "text/html" ||
            h.endsWith(".xhtml") || h.endsWith(".html") || h.endsWith(".htm")
    }

    private fun decode(href: String) = try {
        URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        href
    }

    /** a/../b → b. Paths come from the file: nothing outside the zip can be reached anyway. */
    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (p in path.substringBefore('#').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += p
            }
        }
        return out.joinToString("/")
    }

    private fun ZipFile.read(name: String): ByteArray =
        readOrNull(name) ?: throw java.io.IOException("missing $name")

    private fun ZipFile.readOrNull(name: String): ByteArray? {
        val e = getEntry(name) ?: return null
        return getInputStream(e).use { it.readBytes() }
    }
}
