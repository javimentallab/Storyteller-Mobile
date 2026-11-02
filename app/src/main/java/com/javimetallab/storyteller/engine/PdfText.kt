package com.javimetallab.storyteller.engine

import com.tom_roush.fontbox.afm.AFMParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDSimpleFont
import com.tom_roush.pdfbox.pdmodel.font.encoding.GlyphList
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.PriorityQueue
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * PDF to a translated EPUB, without the layout. A port of PdfDoc of the desktop app, which reads with
 * pdfminer.six: PdfBox only hands over the glyphs and PdfLayout groups them as pdfminer does (pdfminer's
 * default LAParams), so a PDF gives the same blocks here. sample.golden.json comes out of the desktop engine.
 */

internal open class PdfArea(
    var x0: Double = Double.POSITIVE_INFINITY,
    var y0: Double = Double.POSITIVE_INFINITY,
    var x1: Double = Double.NEGATIVE_INFINITY,
    var y1: Double = Double.NEGATIVE_INFINITY,
) {
    var id = 0      // pdfminer's id(obj), the tie-break between equal distances
    val width get() = x1 - x0
    val height get() = y1 - y0

    fun grow(o: PdfArea) {
        x0 = min(x0, o.x0)
        y0 = min(y0, o.y0)
        x1 = max(x1, o.x1)
        y1 = max(y1, o.y1)
    }
}

/** A glyph, in the box pdfminer gives it. */
internal class PdfChar(
    val text: String, x0: Double, y0: Double, x1: Double, y1: Double, val size: Double = y1 - y0, val upright: Boolean = true,
) : PdfArea(x0, y0, x1, y1)

internal class PdfLine : PdfArea() {
    val chars = ArrayList<PdfChar>()
    val text = StringBuilder()
    private var lastX1 = Double.POSITIVE_INFINITY

    fun add(c: PdfChar) {
        if (lastX1 < c.x0 - PdfLayout.WORD_MARGIN * max(c.width, c.height)) text.append(' ')
        lastX1 = c.x1
        chars += c
        text.append(c.text)
        grow(c)
    }

    val isEmpty get() = width <= 0 || height <= 0 || (text.isNotEmpty() && text.all { it.isWhitespace() })
}

internal class PdfTextBox : PdfArea() {
    val lines = ArrayList<PdfLine>()

    fun add(l: PdfLine) {
        lines += l
        grow(l)
    }
}

/** pdfminer's Plane: finds what is in an area, by cells of 50 points. */
private class Plane<T : PdfArea>(private val w: Double, private val h: Double) {
    private val seq = ArrayList<T>()
    private val live = HashSet<T>()
    private val grid = HashMap<Long, ArrayList<T>>()

    private inline fun cells(x0: Double, y0: Double, x1: Double, y1: Double, f: (Long) -> Unit) {
        if (x1 <= 0 || w <= x0 || y1 <= 0 || h <= y0) return
        val xa = max(0.0, x0).toInt().floorDiv(CELL)
        val xb = (min(w, x1) + CELL).toInt().floorDiv(CELL)
        val ya = max(0.0, y0).toInt().floorDiv(CELL)
        val yb = (min(h, y1) + CELL).toInt().floorDiv(CELL)
        for (y in ya until yb) for (x in xa until xb) f((y.toLong() shl 32) or x.toLong())
    }

    fun add(o: T) {
        cells(o.x0, o.y0, o.x1, o.y1) { grid.getOrPut(it) { ArrayList() } += o }
        seq += o
        live += o
    }

    fun remove(o: T) {
        cells(o.x0, o.y0, o.x1, o.y1) { grid[it]?.remove(o) }
        live -= o
    }

    fun alive(): List<T> = seq.filter { it in live }

    fun find(x0: Double, y0: Double, x1: Double, y1: Double): List<T> {
        val seen = HashSet<T>()
        val out = ArrayList<T>()
        cells(x0, y0, x1, y1) { k ->
            for (o in grid[k].orEmpty()) {
                if (!seen.add(o)) continue
                if (o.x1 <= x0 || x1 <= o.x0 || o.y1 <= y0 || y1 <= o.y0) continue
                out += o
            }
        }
        return out
    }

    private companion object {
        const val CELL = 50
    }
}

/** LTLayoutContainer.analyze of pdfminer, for horizontal text (the desktop app leaves detect_vertical off). */
internal object PdfLayout {
    const val WORD_MARGIN = 0.1
    private const val LINE_OVERLAP = 0.5
    private const val CHAR_MARGIN = 2.0
    private const val LINE_MARGIN = 0.5
    private const val BOXES_FLOW = 0.5

    // Pairs of boxes are all kept in memory. A page beyond this is a table or a map: read it top down.
    private const val MAX_BOXES = 1000

    private class Group(a: PdfArea, b: PdfArea) : PdfArea() {
        val objs = arrayListOf(a, b)

        init {
            grow(a)
            grow(b)
        }
    }

    private class Pending(val blocked: Boolean, val d: Double, val a: PdfArea, val b: PdfArea)

    /** The text boxes of a page, in reading order. */
    fun boxes(chars: List<PdfChar>, width: Double, height: Double): List<PdfTextBox> {
        if (chars.isEmpty()) return emptyList()
        val boxes = group(lines(chars).filterNot { it.isEmpty }, width, height)
        boxes.forEach { b -> b.lines.sortBy { -it.y1 } }
        if (boxes.size > MAX_BOXES) return boxes.sortedWith(compareBy({ -it.y0 }, { it.x0 }))
        return order(boxes, width, height)
    }

    private fun lines(chars: List<PdfChar>): List<PdfLine> {
        val out = ArrayList<PdfLine>()
        var a: PdfChar? = null
        var line: PdfLine? = null
        for (b in chars) {
            val first = a
            if (first != null) {
                val overlap = if (first.y0 <= b.y1 && b.y0 <= first.y1) min(abs(first.y0 - b.y1), abs(first.y1 - b.y0)) else -1.0
                val apart = if (b.x0 <= first.x1 && first.x0 <= b.x1) 0.0 else min(abs(first.x0 - b.x1), abs(first.x1 - b.x0))
                val aligned = overlap >= 0 && min(first.height, b.height) * LINE_OVERLAP < overlap &&
                    apart < max(first.width, b.width) * CHAR_MARGIN
                val open = line
                when {
                    aligned && open != null -> open.add(b)
                    open != null -> {
                        out += open
                        line = null
                    }
                    aligned -> line = PdfLine().apply {
                        add(first)
                        add(b)
                    }
                    else -> out += PdfLine().apply { add(first) }
                }
            }
            a = b
        }
        out += line ?: PdfLine().apply { add(a!!) }
        return out
    }

    private fun group(lines: List<PdfLine>, width: Double, height: Double): List<PdfTextBox> {
        val plane = Plane<PdfLine>(width, height)
        lines.forEach(plane::add)
        val boxes = HashMap<PdfLine, PdfTextBox>()
        for (line in lines) {
            val members = arrayListOf(line)
            val d = LINE_MARGIN * line.height
            for (o in plane.find(line.x0, line.y0 - d, line.x1, line.y1 + d)) {
                val alike = abs(o.height - line.height) <= d && (
                    abs(o.x0 - line.x0) <= d || abs(o.x1 - line.x1) <= d ||
                        abs((o.x0 + o.x1) / 2 - (line.x0 + line.x1) / 2) <= d
                    )
                if (!alike) continue
                members += o
                boxes.remove(o)?.let { members += it.lines }
            }
            val box = PdfTextBox()
            for (m in LinkedHashSet(members)) {
                box.add(m)
                boxes[m] = box
            }
        }
        val seen = HashSet<PdfTextBox>()
        return lines.mapNotNull { boxes[it] }.filter { seen.add(it) && it.width > 0 && it.height > 0 }
    }

    /** Merges the closest boxes until one group is left; the tree gives the reading order. */
    private fun order(boxes: List<PdfTextBox>, width: Double, height: Double): List<PdfTextBox> {
        fun dist(a: PdfArea, b: PdfArea) =
            (max(a.x1, b.x1) - min(a.x0, b.x0)) * (max(a.y1, b.y1) - min(a.y0, b.y0)) - a.width * a.height - b.width * b.height

        var ids = 0
        boxes.forEach { it.id = ids++ }
        val heap = PriorityQueue<Pending>(
            max(1, boxes.size * boxes.size / 2),
            compareBy<Pending> { it.blocked }.thenBy { it.d }.thenBy { it.a.id }.thenBy { it.b.id },
        )
        for (i in boxes.indices) for (j in i + 1 until boxes.size) heap += Pending(false, dist(boxes[i], boxes[j]), boxes[i], boxes[j])
        val plane = Plane<PdfArea>(width, height)
        boxes.forEach(plane::add)
        val done = HashSet<PdfArea>()
        while (true) {
            val p = heap.poll() ?: break
            if (p.a in done || p.b in done) continue
            if (!p.blocked) {
                val between = plane.find(min(p.a.x0, p.b.x0), min(p.a.y0, p.b.y0), max(p.a.x1, p.b.x1), max(p.a.y1, p.b.y1))
                if (between.any { it !== p.a && it !== p.b }) {
                    heap += Pending(true, p.d, p.a, p.b)
                    continue
                }
            }
            val g = Group(p.a, p.b).apply { id = ids++ }
            plane.remove(p.a)
            plane.remove(p.b)
            done += p.a
            done += p.b
            for (o in plane.alive()) heap += Pending(false, dist(g, o), g, o)
            plane.add(g)
        }
        val out = ArrayList<PdfTextBox>()
        fun walk(o: PdfArea) {
            when (o) {
                is PdfTextBox -> out += o
                is Group -> {
                    o.objs.sortBy { (1 - BOXES_FLOW) * it.x0 - (1 + BOXES_FLOW) * (it.y0 + it.y1) }
                    o.objs.forEach(::walk)
                }
            }
        }
        plane.alive().forEach(::walk)
        return out
    }
}

/** One page, as the reader leaves it. */
internal class PdfPage(val chars: List<PdfChar>, val width: Double, val height: Double)

internal class PdfBlock(
    val page: Int, val height: Double, val top: Double, val bottom: Double, val x0: Double, val x1: Double, val size: Double, var text: String,
)

internal class PdfChapter(val title: String, val paras: List<Pair<String, String>>)

/** From glyphs to blocks, and from blocks to chapters: PdfDoc of the desktop app, line by line. */
internal class PdfBook(source: Sequence<PdfPage>) {
    val blocks = ArrayList<PdfBlock>()
    var pages = 0
        private set

    init {
        val raw = ArrayList<PdfBlock>()
        for (page in source) {
            for (box in PdfLayout.boxes(page.chars, page.width, page.height)) {
                val sizes = ArrayList<Double>()
                var upright = 0
                for (line in box.lines) {
                    for (c in line.chars) {
                        if (c.text.isBlank()) continue
                        sizes += c.size
                        if (c.upright) upright++
                    }
                }
                val text = join(box.lines.map { it.text.toString() })
                if (sizes.isEmpty() || upright < sizes.size / 2.0 || !Translation.needsTranslation(text)) continue
                raw += PdfBlock(
                    pages, page.height, page.height - box.y1, page.height - box.y0, box.x0, box.x1,
                    sizes.sorted()[sizes.size / 2], text,
                )
            }
            pages++
        }
        val body = if (raw.isEmpty()) 11.0 else raw.map { it.size }.sorted()[raw.size / 2]
        val merged = HashSet<PdfBlock>()
        // A drop cap starts the block on its right at its height; it doesn't come right before its paragraph.
        for (cap in raw.filter { points(it.text) <= 2 && it.size >= body * HEADING }) {
            val near = raw.filter {
                it.page == cap.page && it.size < cap.size && it.top < cap.bottom && it.bottom > cap.top && it.x1 > cap.x1
            }
            val dest = near.minByOrNull { abs(it.top - cap.top) } ?: continue
            dest.text = cap.text + dest.text
            merged += cap
        }
        raw.filterTo(blocks) { it !in merged }
    }

    val units get() = blocks.map { b -> DocUnit(b.text) { b.text = it } }

    /** Chapters by the headings; running heads, feet and page numbers stay out. */
    fun chapters(title: String): List<PdfChapter> {
        val count = HashMap<Pair<Long, String>, Int>()
        fun sign(b: PdfBlock, t: String) = Math.rint(b.top / 8).toLong() to prefix(t, 40)
        for (b in blocks) count.merge(sign(b, b.text.trim()), 1, Int::plus)
        val body = if (blocks.isEmpty()) 11.0 else blocks.map { it.size }.sorted()[blocks.size / 2]

        class Draft(val title: String) {
            val paras = ArrayList<Triple<String, String, Int>>()
        }

        var chapters = ArrayList<Draft>()
        var cur: Draft? = null
        for (b in blocks) {
            val t = b.text.trim()
            val repeated = count[sign(b, t)]!! >= max(3, pages / 4)
            val margin = b.bottom < b.height * 0.08 || b.top > b.height * 0.92
            if ((repeated && margin) || ONLY_NUMBER.matches(t)) continue
            val heading = b.size >= body * HEADING && points(t) in 3 until 160
            val open = cur
            if (heading && (open == null || open.paras.any { it.first == "p" })) {
                cur = Draft(t).also { chapters.add(it) }
                continue
            }
            val into = open ?: Draft(title).also {
                chapters.add(it)
                cur = it
            }
            into.paras += Triple(if (heading) "h" else "p", t, b.page)
        }
        if (chapters.size == 1 && pages > 15) {     // no headings: a chapter every 10 pages
            val by10 = sortedMapOf<Int, Draft>()
            for (p in chapters[0].paras) by10.getOrPut(p.third / 10) { Draft("$title (${p.third / 10 + 1})") }.paras += p
            chapters = ArrayList(by10.values)
        }
        if (chapters.isEmpty()) chapters += Draft(title)
        return chapters.map { c -> PdfChapter(c.title, c.paras.map { it.first to it.second }) }
    }

    private companion object {
        const val HEADING = 1.2
        val CID = Regex("\\(cid:\\d+\\)")
        val LIGATURE = Regex("[\\uFB00-\\uFB06]")
        val ONLY_NUMBER = Regex("[^\\p{L}\\p{N}_]*\\p{Nd}{1,4}[^\\p{L}\\p{N}_]*")

        fun clean(t: String) =
            LIGATURE.replace(CID.replace(t, "")) { Normalizer.normalize(it.value, Normalizer.Form.NFKC) }

        fun join(lines: List<String>): String {
            var out = ""
            for (raw in lines) {
                val l = clean(raw).trim()
                if (l.isEmpty()) continue
                out = when {
                    out.endsWith("-") && Character.isLowerCase(l.codePointAt(0)) -> out.dropLast(1) + l
                    out.isNotEmpty() -> "$out $l"
                    else -> l
                }
            }
            return Docs.ws(out.replace('\r', ' ').replace('\u000B', ' '))
        }

        fun points(t: String) = t.codePointCount(0, t.length)

        fun prefix(t: String, n: Int) = if (points(t) <= n) t else t.substring(0, t.offsetByCodePoints(0, n))
    }
}

/** Hands over the glyphs of each page. Without Do: pdfminer leaves forms out too, and images are of no use. */
private class GlyphReader : PDFStreamEngine() {
    private var glyphs = ArrayList<PdfChar>()
    private var pa = 1.0
    private var pb = 0.0
    private var pc = 0.0
    private var pd = 1.0
    private var pe = 0.0
    private var pf = 0.0

    init {
        listOf(
            BeginText(), Concatenate(), EndText(), SetGraphicsStateParameters(), Save(), Restore(), NextLine(),
            SetCharSpacing(), MoveText(), MoveTextSetLeading(), SetFontAndSize(), ShowText(), ShowTextAdjusted(),
            SetTextLeading(), SetMatrix(), SetTextRenderingMode(), SetTextRise(), SetWordSpacing(),
            SetTextHorizontalScaling(), ShowTextLine(), ShowTextLineAndSpace(),
        ).forEach(::addOperator)
    }

    fun read(page: PDPage): PdfPage {
        val box = page.mediaBox
        val x0 = box.lowerLeftX.toDouble()
        val y0 = box.lowerLeftY.toDouble()
        val x1 = box.upperRightX.toDouble()
        val y1 = box.upperRightY.toDouble()
        val rotation = page.rotation
        when (rotation) {
            90 -> setPage(0.0, -1.0, 1.0, 0.0, -y0, x1)
            180 -> setPage(-1.0, 0.0, 0.0, -1.0, x1, y1)
            270 -> setPage(0.0, 1.0, -1.0, 0.0, y1, -x0)
            else -> setPage(1.0, 0.0, 0.0, 1.0, -x0, -y0)
        }
        glyphs = ArrayList()
        processPage(page)
        val sideways = rotation == 90 || rotation == 270
        val w = x1 - x0
        val h = y1 - y0
        return PdfPage(glyphs, if (sideways) h else w, if (sideways) w else h)
    }

    override fun showText(string: ByteArray) {
        graphicsState.textState.font?.let(::giveWidths)
        super.showText(string)
    }

    /**
     * A standard font with its own /Differences and no /Widths: PdfBox measures it by its substitute,
     * always sans serif on Android, and the line falls apart. Its metrics are what the PDF counts on.
     */
    private fun giveWidths(font: PDFont) {
        if (font !is PDSimpleFont) return
        val dict = font.cosObject
        if (dict.containsKey(COSName.WIDTHS) || font.isEmbedded || font.isStandard14 || font.name !in STANDARD) return
        val afm = data("com/tom_roush/pdfbox/resources/afm/${font.name}.afm").buffered().use { AFMParser(it).parse(true) }
        val widths = COSArray()
        for (code in 0..255) {
            val width = when (val name = font.encoding.getName(code)) {
                ".notdef" -> 250f
                "nbspace" -> afm.getCharacterWidth("space")
                "sfthyphen" -> afm.getCharacterWidth("hyphen")
                else -> afm.getCharacterWidth(name)
            }
            widths.add(COSFloat(width))
        }
        dict.setInt(COSName.FIRST_CHAR, 0)
        dict.setInt(COSName.LAST_CHAR, 255)
        dict.setItem(COSName.WIDTHS, widths)
    }

    private fun setPage(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        pa = a
        pb = b
        pc = c
        pd = d
        pe = e
        pf = f
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        val m = textRenderingMatrix
        val a = m.getValue(0, 0).toDouble()
        val b = m.getValue(0, 1).toDouble()
        val c = m.getValue(1, 0).toDouble()
        val d = m.getValue(1, 1).toDouble()
        val e = m.getValue(2, 0).toDouble()
        val f = m.getValue(2, 1).toDouble()
        // The box of the glyph in glyph space: an em high and as wide as it advances, from its descent.
        // PdfBox has already moved a vertical glyph to the corner of its em square.
        val vertical = font.isVertical
        val adv = if (vertical) 1.0 else displacement.x.toDouble()
        val bottom = if (vertical) 0.0 else -abs((font.fontDescriptor?.descent ?: 0f).toDouble()) / 1000
        val top = bottom + 1
        val xs = doubleArrayOf(c * bottom + e, a * adv + c * bottom + e, a * adv + c * top + e, c * top + e)
        val ys = doubleArrayOf(d * bottom + f, b * adv + d * bottom + f, b * adv + d * top + f, d * top + f)
        var left = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        var high = Double.NEGATIVE_INFINITY
        for (i in 0..3) {
            val x = pa * xs[i] + pc * ys[i] + pe
            val y = pb * xs[i] + pd * ys[i] + pf
            left = min(left, x)
            right = max(right, x)
            low = min(low, y)
            high = max(high, y)
        }
        val ra = a * pa + b * pc
        val rb = a * pb + b * pd
        val rc = c * pa + d * pc
        val rd = c * pb + d * pd
        glyphs += PdfChar(
            unicode(font, code), left, low, right, high,
            if (vertical) right - left else high - low, ra * rd > 0 && rb * rc <= 0,
        )
    }

    /** With no name for it: a simple font's letter falls back on its code; anything else, pdfminer's mark, later dropped. */
    private fun unicode(font: PDFont, code: Int): String =
        font.toUnicode(code, NAMES) ?: if (font is PDSimpleFont && code in 32..126) code.toChar().toString() else "(cid:$code)"

    private companion object {
        val STANDARD = setOf(
            "Courier", "Courier-Bold", "Courier-Oblique", "Courier-BoldOblique",
            "Helvetica", "Helvetica-Bold", "Helvetica-Oblique", "Helvetica-BoldOblique",
            "Times-Roman", "Times-Bold", "Times-Italic", "Times-BoldItalic", "Symbol", "ZapfDingbats",
        )

        val NAMES: GlyphList by lazy {
            data("com/tom_roush/pdfbox/resources/glyphlist/additional.txt").use { GlyphList(GlyphList.getAdobeGlyphList(), it) }
        }

        /** PdfBox's own data: in the assets, or on the classpath in the JVM tests. */
        fun data(path: String): InputStream =
            if (PDFBoxResourceLoader.isReady()) PDFBoxResourceLoader.getStream(path) else GlyphList::class.java.getResourceAsStream("/$path")!!
    }
}

/** A PDF as a document to translate: its blocks go in, and an EPUB comes out. */
internal class PdfDoc(file: File, private val tgt: String, title: String, onPage: () -> Unit = {}) : Doc {
    private val title = title.replace(Regex("\\s*\\([a-z]{2}\\)$"), "")
    val book = read(file, onPage)
    override val units = book.units

    private fun read(file: File, onPage: () -> Unit): PdfBook = try {
        // Fonts and streams on disk, next to the file: a phone has little heap and this PDF can be huge.
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(file.absoluteFile.parentFile)
        PDDocument.load(file, memory).use { doc ->
            val reader = GlyphReader()
            PdfBook(
                doc.pages.asSequence().map {
                    onPage()
                    reader.read(it)
                },
            )
        }
    } catch (e: LinkageError) {     // a PDF locked to a certificate: Bouncy Castle isn't in the app
        throw IOException(e)
    } catch (e: VirtualMachineError) {
        throw IOException(e)
    }

    override fun save(out: File) = PdfEpub.write(out, title, tgt, book.chapters(title))
}

internal object PdfEpub {
    private fun esc(t: String) = t.filter { it >= ' ' || it == '\t' || it == '\n' || it == '\r' }
        .replace("￾", "").replace("￿", "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;")

    private const val XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"

    fun write(out: File, title: String, lang: String, chapters: List<PdfChapter>) {
        val files = LinkedHashMap<String, String>()
        files["mimetype"] = "application/epub+zip"
        files["META-INF/container.xml"] =
            "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>"
        val items = StringBuilder()
        val spine = StringBuilder()
        val nav = StringBuilder()
        chapters.forEachIndexed { k, ch ->
            val i = k + 1
            val name = "c${i.toString().padStart(3, '0')}.xhtml"
            val paras = ch.paras.joinToString("\n") { (kind, t) ->
                val tag = if (kind == "h") "h3" else "p"
                "<$tag>${esc(t)}</$tag>"
            }
            files["OEBPS/$name"] = XML + "<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"$lang\" lang=\"$lang\"><head>" +
                "<title>${esc(ch.title)}</title></head><body><h2>${esc(ch.title)}</h2>\n$paras\n</body></html>"
            items.append("<item id=\"c$i\" href=\"$name\" media-type=\"application/xhtml+xml\"/>")
            spine.append("<itemref idref=\"c$i\"/>")
            nav.append("<li><a href=\"$name\">${esc(ch.title)}</a></li>")
        }
        files["OEBPS/nav.xhtml"] = XML + "<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" " +
            "xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\"$lang\"><head><title>${esc(title)}</title></head>" +
            "<body><nav epub:type=\"toc\"><ol>$nav</ol></nav></body></html>"
        val modified = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        files["OEBPS/content.opf"] = XML + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">" +
            "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"uid\">urn:uuid:${UUID.randomUUID()}</dc:identifier>" +
            "<dc:title>${esc(title)}</dc:title><dc:language>$lang</dc:language>" +
            "<meta property=\"dcterms:modified\">$modified</meta></metadata>" +
            "<manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>$items</manifest>" +
            "<spine>$spine</spine></package>"
        Docs.rewriteZip(files.keys.toList(), { files[it]!!.toByteArray(Charsets.UTF_8) }, out)
    }
}
