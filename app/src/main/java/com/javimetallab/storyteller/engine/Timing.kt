package com.javimetallab.storyteller.engine

import com.javimetallab.storyteller.data.Segment
import com.javimetallab.storyteller.data.Transcript
import com.javimetallab.storyteller.data.Word
import kotlin.math.abs

/** Cuts a long audio in pieces for the transcription model, at a quiet moment if it can. */
class Chunker(private val rate: Int, seconds: Int) {
    private val size = rate * seconds
    private val lookBack = rate * LOOK_BACK_SECONDS
    private val window = rate / 5                 // 200 ms
    private val buf = ShortArray(size)
    private var count = 0

    fun add(samples: ShortArray, emit: (ShortArray, Int) -> Unit) {
        var from = 0
        while (from < samples.size) {
            val n = minOf(size - count, samples.size - from)
            samples.copyInto(buf, count, from, from + n)
            count += n
            from += n
            if (count == size) {
                val cut = quietestCut()
                emit(buf, cut)
                val rest = count - cut
                buf.copyInto(buf, 0, cut, count)
                count = rest
            }
        }
    }

    fun finish(emit: (ShortArray, Int) -> Unit) {
        if (count > 0) emit(buf, count)
        count = 0
    }

    /** Where the last few seconds are quietest: a word cut in two is a word lost twice. */
    private fun quietestCut(): Int {
        var best = count
        var bestEnergy = Long.MAX_VALUE
        var at = (count - lookBack).coerceAtLeast(window)
        while (at + window <= count) {
            var e = 0L
            var i = at
            while (i < at + window) {
                e += kotlin.math.abs(buf[i].toInt())
                i += 8
            }
            if (e < bestEnergy) {
                bestEnergy = e
                best = at + window / 2
            }
            at += window / 2
        }
        return best
    }

    private companion object {
        const val LOOK_BACK_SECONDS = 8
    }
}

object Timing {
    /**
     * The timed segments of a transcript, a sentence each when it can. With the time of each word
     * a sentence starts when its first word does. Without them it gets a share of the time according
     * to its length: enough to follow along, but it can be seconds off in a slow passage.
     */
    fun segments(t: Transcript, seconds: Double): List<Segment> {
        // The whole text at once: Whisper can end a segment in the middle of a word or a sentence.
        byWords(t.text.ifBlank { t.segments.joinToString(" ") { it.text } }, t.words)?.let { return it }
        if (t.segments.isEmpty()) return share(t.text, 0.0, seconds)
        // Some servers give long stretches: 23 s lit up at once isn't following along.
        return t.segments.flatMap { s ->
            if (s.end - s.start > LONG_SEGMENT_SECONDS) share(s.text, s.start, s.end).ifEmpty { listOf(s) } else listOf(s)
        }
    }

    /** Null when the words aren't there or aren't those of the text: then they say nothing about it. */
    private fun byWords(text: String, words: List<Word>): List<Segment>? {
        val want = letters(text)
        val have = words.sumOf { letters(it.text) }
        if (want == 0 || have == 0 || abs(want - have) > maxOf(4, want / 10)) return null
        val scale = have.toDouble() / want              // a small difference doesn't pile up at the end
        val out = ArrayList<Segment>()
        var next = 0
        var left = 0.0
        for (sentence in TextPrep.splitSentences(text)) {
            val first = next
            left += letters(sentence) * scale
            while (next < words.size && left > 0.5) left -= letters(words[next++].text)
            if (next > first) {
                out += Segment(words[first].start, words[next - 1].end, sentence)
            } else if (out.isNotEmpty()) {               // the words ran out: it goes with the one before
                out[out.lastIndex] = out.last().let { it.copy(text = "${it.text} $sentence") }
            }
        }
        if (next < words.size && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(end = words.last().end)
        return out
    }

    /** Only letters and digits: some services leave the punctuation out of the words. */
    private fun letters(s: String) = s.count { it.isLetterOrDigit() }

    private fun share(text: String, from: Double, to: Double): List<Segment> {
        val sentences = TextPrep.splitSentences(text)
        val chars = sentences.sumOf { it.length }
        if (chars == 0) return emptyList()
        var at = from
        return sentences.map { s ->
            val part = (to - from) * s.length / chars
            Segment(at, at + part, s).also { at += part }
        }
    }

    private const val LONG_SEGMENT_SECONDS = 12.0
}
