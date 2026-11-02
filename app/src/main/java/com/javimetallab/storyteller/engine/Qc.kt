package com.javimetallab.storyteller.engine

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Listens to what came back and says whether it is what was asked for. XTTS sometimes invents an
 * ending or babbles on, and only regenerating fixes it. A port of the QC in cc_engine.py of the
 * desktop app; golden.json checks both give the same verdict.
 */
object Qc {
    const val THRESHOLD = 0.85
    const val RETRIES = 2
    private const val TAIL_MAX = 0.30       // seconds of voice allowed after the last word heard

    data class Verdict(val recall: Double, val ok: Boolean, val score: Double, val reason: String)

    private val NO_SPACES = Regex("[\\u3040-\\u30ff\\u3400-\\u9fff\\uf900-\\ufaff]")
    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val LETTER = Regex("\\p{L}")

    /** The words of a text, without accents, in any alphabet. Chinese and Japanese go character by character. */
    fun words(s: String): List<String> {
        val plain = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return WORD.findAll(NO_SPACES.replace(plain) { " ${it.value} " }).map { it.value }.toList()
    }

    /** difflib.SequenceMatcher(None, a, b).ratio() */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        fun matches(alo: Int, ahi: Int, blo: Int, bhi: Int): Int {
            var besti = alo
            var bestj = blo
            var best = 0
            var row = HashMap<Int, Int>()
            for (i in alo until ahi) {
                val next = HashMap<Int, Int>()
                for (j in blo until bhi) {
                    if (b[j] != a[i]) continue
                    val k = (row[j - 1] ?: 0) + 1
                    next[j] = k
                    if (k > best) {
                        besti = i - k + 1
                        bestj = j - k + 1
                        best = k
                    }
                }
                row = next
            }
            if (best == 0) return 0
            return best + matches(alo, besti, blo, bestj) + matches(besti + best, ahi, bestj + best, bhi)
        }
        return 2.0 * matches(0, a.length, 0, b.length) / (a.length + b.length)
    }

    /** At the end of the sentence, which is where XTTS fails: (invented words after the last one, is the end missing?). */
    private fun tail(rf: List<String>, rh: List<String>): Pair<Int, Boolean> {
        if (rf.isEmpty() || rh.isEmpty()) return 0 to false
        fun close(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && ratio(a, b) >= 0.75)
        for ((back, w) in rf.takeLast(2).reversed().withIndex()) {
            var j = rh.size - 1
            val stop = max(-1, rh.size - 12)
            while (j > stop) {
                if (close(rh[j], w)) {
                    // Only the one before the last is there, with nothing behind: the last one got eaten.
                    val eaten = back == 1 && j == rh.size - 1 && rf.last().length > 3 && rh.last().none { it.isDigit() }
                    return max(0, rh.size - 1 - j - back) to eaten
                }
                j--
            }
        }
        // Not there: Whisper sometimes joins or splits words, or writes figures.
        val end = rh.takeLast(4).joinToString("")
        if (rf.last() in end || rf.last().length <= 3 || end.any { it.isDigit() }) return 0 to false
        return 0 to true
    }

    fun eval(ref: String, heard: String, threshold: Double = THRESHOLD, fault: String = ""): Verdict {
        val rf = words(ref)
        val rh = words(heard)
        if (rf.isEmpty()) return Verdict(1.0, true, 1.0, "")
        val want = rf.toSet()
        val have = rh.toSet()
        val recall = want.count { it in have }.toDouble() / want.size
        val cr = rf.groupingBy { it }.eachCount()
        val ch = rh.groupingBy { it }.eachCount()
        val repeated = ch.maxOfOrNull { (w, n) -> n - (cr[w] ?: 0) } ?: 0
        val tooLong = rh.size > rf.size * 1.5 + 5
        val (extra, endMissing) = tail(rf, rh)
        val invented = repeated >= 4 || tooLong || extra >= 3 || fault.isNotEmpty()
        val ok = recall >= threshold && !invented && !endMissing
        val score = recall - (if (invented) 0.6 else 0.0) - (if (endMissing) 0.3 else 0.0) -
            0.02 * max(0, repeated - 1) - 0.03 * extra
        val reason = when {
            fault.isNotEmpty() -> fault
            repeated >= 4 -> "repeats x$repeated"
            tooLong -> "text x%.1f".format(rh.size.toDouble() / rf.size)
            extra >= 3 -> "$extra invented words"
            endMissing -> "eats the end"
            else -> ""
        }
        return Verdict(recall, ok, score, reason)
    }

    /** How fast the good clips of this book go, in letters per second: to judge if another one lasts too long. */
    class Pace {
        private val rates = ArrayList<Double>()

        @Synchronized
        fun note(text: String, pcm: Pcm) {
            val letters = LETTER.findAll(text).count()
            if (pcm.seconds > 0 && letters >= 15) {
                rates += letters / pcm.seconds
                if (rates.size > 200) rates.removeAt(0)
            }
        }

        @Synchronized
        fun expected(text: String): Double? {
            if (rates.size < 8) return null
            val sorted = rates.sorted()
            val m = sorted.size / 2
            val median = if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2
            return LETTER.findAll(text).count() / median
        }
    }

    /**
     * What the transcription doesn't see: XTTS sometimes goes on babbling without forming words and
     * the text comes out perfect. Voice after the last word, or a clip far too long for its letters.
     */
    fun audioFault(text: String, pcm: Pcm, lastEnd: Double?, pace: Pace): String {
        val s = pcm.samples
        if (s.isEmpty()) return ""
        if (lastEnd != null) {
            val frame = max(1, (0.01 * pcm.rate).toInt())
            val from = (lastEnd * pcm.rate).toInt().coerceIn(0, s.size)
            val frames = (s.size - from) / frame
            if (frames > 0) {
                var all = 0.0
                for (v in s) all += (v / 32768.0).pow(2)
                val level = sqrt(all / s.size) * 10.0.pow(-18.0 / 20)
                var voiced = 0
                for (f in 0 until frames) {
                    var sum = 0.0
                    for (i in 0 until frame) sum += (s[from + f * frame + i] / 32768.0).pow(2)
                    if (sqrt(sum / frame + 1e-12) > level) voiced++
                }
                val seconds = voiced * 0.01
                if (seconds > TAIL_MAX) return "%.1f s of voice without words at the end".format(seconds)
            }
        }
        val expected = pace.expected(text) ?: return ""
        return if (pcm.seconds > expected * 1.25 + 0.4) "lasts %.1f s and should be %.1f s".format(pcm.seconds, expected) else ""
    }
}
