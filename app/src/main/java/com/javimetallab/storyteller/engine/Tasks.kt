package com.javimetallab.storyteller.engine

import android.content.Context
import android.net.Uri
import com.javimetallab.storyteller.data.Capability
import com.javimetallab.storyteller.data.Languages
import com.javimetallab.storyteller.data.Library
import com.javimetallab.storyteller.data.ModelApi
import com.javimetallab.storyteller.data.ModelException
import com.javimetallab.storyteller.data.Prefs
import com.javimetallab.storyteller.data.PronStore
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val SECONDS_PER_REPORT = 1.0

private fun cueJson(start: Double, end: Double, i: Int, text: String) =
    JSONObject().put("i", i).put("start", start).put("end", end).put("text", text)

private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

/** Narrates an EPUB already copied to the book's folder as source.epub. */
class NarrateTask(
    private val context: Context,
    private val book: File,
    title: String,
    private val resume: Boolean,
) : Task("narrate", title, bookId = book.name) {
    private val temp = File(context.cacheDir, "narrate").also { it.mkdirs() }
    private val entries = LinkedHashMap<Int, JSONObject>()
    private var total = 0
    private var sentenceCount = 0

    override fun discard() {
        if (!resume) book.deleteRecursively()
    }

    override fun resumed() = NarrateTask(context, book, title, resume = true)

    private fun saveManifest() {
        val a = JSONArray()
        entries.toSortedMap().values.forEach { a.put(it) }
        Library.writeAtomic(File(book, "manifest.json"), a.toString())
    }

    private fun saveState(complete: Boolean) {
        Library.writeAtomic(
            File(book, "gen_state.json"),
            JSONObject().put("total", total).put("sentences", sentenceCount).put("complete", complete).toString(),
        )
    }

    private fun loadPrevious() {
        val a = Library.readIndex(book) ?: return
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { entries[it.optInt("id")] = it }
    }

    override suspend fun run(ctl: Control) {
        ctl.progress { it.copy(stage = Stage.READING) }
        val endpoint = Prefs.get(context).endpoint(Capability.NARRATION)
        val api = ctl.watch(ModelApi(endpoint))
        val epub = Epub.read(File(book, "source.epub"))
        if (epub.title.isNotBlank() && !resume) Library.writeAtomic(File(book, "title.txt"), epub.title)
        val options = try {
            JSONObject(File(book, "options.json").readText())
        } catch (_: Exception) {
            JSONObject()
        }
        val prepareText = options.optBoolean("prepare", true)
        val lang = endpoint.language.ifBlank { Languages.default() }      // the user's choice, like the voice
        val speller = Speller(lang)
        val pron = PronStore.forLanguage(context, lang)
        val listener = Prefs.get(context).endpoint(Capability.TRANSCRIPTION).takeIf { endpoint.qc && it.isSet }
            ?.let { ctl.watch(ModelApi(it)) }
        val pace = Qc.Pace()
        val chapters = epub.chapters
        total = chapters.size
        if (total == 0) throw IOException("no text")
        val sentences = chapters.map { TextPrep.splitChunks(it.text, lang) }
        val allSentences = sentences.sumOf { it.size }.coerceAtLeast(1)
        sentenceCount = allSentences
        if (resume) loadPrevious()
        saveState(false)
        saveManifest()
        val parallel = endpoint.parallel.coerceAtLeast(1)
        var doneSentences = entries.values.sumOf { it.optInt("n") }
        val times = ArrayDeque<Long>()
        for ((ci, ch) in chapters.withIndex()) {
            val id = ci + 1
            val base = "cap%02d".format(id)
            val prev = entries[id]
            if (prev?.optBoolean("done") == true) continue
            val list = sentences[ci]
            if (list.isEmpty()) continue
            val wavFile = File(book, "$base.wav")
            val jsonFile = File(book, "$base.json")
            val cues = JSONArray()
            var rate = prev?.optInt("sr") ?: 0
            var kept = 0L
            var start = 0
            if (prev != null && wavFile.exists() && rate > 0) {       // a chapter left halfway
                kept = prev.optLong("frames")
                val old = try {
                    JSONObject(jsonFile.readText()).optJSONArray("cues")
                } catch (_: Exception) {
                    null
                }
                start = minOf(prev.optInt("n"), old?.length() ?: 0)
                // Narrated with another way of cutting: going on by position would skip or repeat. It starts over.
                val same = start <= list.size && (0 until start).all { old!!.getJSONObject(it).optString("text") == list[it].text }
                if (same) for (i in 0 until start) cues.put(old!!.get(i)) else start = 0
            }
            doneSentences -= prev?.optInt("n") ?: 0
            doneSentences += start
            var writer: WavWriter? = if (rate > 0) WavWriter(wavFile, rate, if (start > 0) kept else 0) else null
            try {
                // Asked for ahead of time, written in order. What had got ahead when it stops is asked for again.
                inOrder(
                    list.size, start, parallel,
                    work = { k ->
                        val text = list[k].text
                        val spoken = if (prepareText) TextPrep.forVoice(text, lang, speller::spell, pron) else text
                        val tmp = File(temp, "${book.name}-$id-$k.audio")
                        val heard = File(temp, "${book.name}-$id-$k.heard.wav")
                        try {
                            var best: Pcm? = null
                            var bestScore = -1e9
                            for (attempt in 0..(if (listener != null) Qc.RETRIES else 0)) {
                                ctl.retrying { api.speech(spoken, tmp, lang) }
                                val pcm = AudioCodec.decodeAll(tmp)
                                if (listener == null) {
                                    best = pcm
                                    break
                                }
                                val said = try {
                                    AudioCodec.writeWav(heard, pcm.samples, pcm.samples.size, pcm.rate)
                                    listener.transcribe(heard, Languages.forWhisper(lang), if (lang == "es") "Lectura de un libro." else ".")
                                } catch (e: IOException) {
                                    ctl.check()
                                    if (best == null) best = pcm      // nobody to ask: what was generated stays
                                    break
                                }
                                val verdict = Qc.eval(spoken, said.text, fault = Qc.audioFault(spoken, pcm, said.words.lastOrNull()?.end, pace))
                                if (verdict.score > bestScore) {
                                    bestScore = verdict.score
                                    best = pcm
                                }
                                if (verdict.ok) {
                                    pace.note(spoken, pcm)
                                    break
                                }
                            }
                            best!!
                        } finally {
                            tmp.delete()
                            heard.delete()
                        }
                    },
                    deliver = { k, pcm ->
                        ctl.check()
                        val w = writer ?: WavWriter(wavFile, pcm.rate).also {
                            writer = it
                            rate = pcm.rate
                        }
                        val from = w.frames.toDouble() / w.rate
                        w.append(pcm.resampledTo(w.rate).samples)
                        val to = w.frames.toDouble() / w.rate
                        cues.put(cueJson(round3(from), round3(to), k, list[k].text))
                        w.silence(list[k].close.pause)
                        doneSentences++
                        w.writeHeader()
                        // Audio first, then what describes it: after a crash, resuming cuts the audio back to this.
                        val dur = w.frames.toDouble() / w.rate
                        Library.writeAtomic(jsonFile, JSONObject().put("title", ch.title).put("duration", round3(dur)).put("cues", cues).toString())
                        entries[id] = JSONObject().put("id", id).put("base", base).put("title", ch.title).put("n", k + 1)
                            .put("duration", round3(dur)).put("done", false).put("sr", w.rate).put("frames", w.frames)
                            .put("file", "$base.wav")
                        saveManifest()
                        times.addLast(System.nanoTime() / 1_000_000)
                        while (times.size > 40) times.removeFirst()
                        val perSentence = if (times.size >= 4) (times.last() - times.first()).toDouble() / (times.size - 1) / 1000 else null
                        val left = allSentences - doneSentences
                        ctl.progress {
                            it.copy(
                                stage = Stage.NARRATING, pct = (doneSentences * 100 / allSentences).coerceAtMost(99),
                                a = id, b = total, c = doneSentences, etaSec = perSentence?.let { p -> p * left },
                            )
                        }
                    },
                )
            } finally {
                writer?.close()
            }
            // The chapter is whole: to a compressed file, which is what stays.
            ctl.progress { it.copy(stage = Stage.ENCODING) }
            val w = writer!!
            val m4a = File(book, "$base.m4a")
            val file = try {
                AudioCodec.wavToM4a(wavFile, m4a, w.rate)
                m4a.name
            } catch (_: Exception) {
                wavFile.name                    // a phone with no encoder: it stays as WAV, and plays
            }
            val dur = w.frames.toDouble() / w.rate
            entries[id] = JSONObject().put("id", id).put("base", base).put("title", ch.title).put("n", list.size)
                .put("duration", round3(dur)).put("done", true).put("sr", w.rate).put("frames", 0).put("file", file)
            saveManifest()
            if (file != wavFile.name) wavFile.delete()
            Work.changed.value = Work.changed.value + 1
        }
        saveState(true)
    }
}

/** Copies an audiobook to its folder and transcribes it, piece by piece. */
class TranscribeTask(
    private val context: Context,
    private val book: File,
    title: String,
    private val source: Uri?,
    private val extension: String,
    private var language: String?,
) : Task("transcribe", title, bookId = book.name) {
    private val resume = source == null
    private val cueList = JSONArray()
    private var fileName = "cap01.$extension"

    override fun discard() {
        if (!resume) book.deleteRecursively()
    }

    /** Until the first piece is saved there is nothing to go on from: it starts again from its file. */
    override fun resumed() = if (File(book, "gen_state.json").exists()) {
        TranscribeTask(context, book, title, null, "", null)
    } else {
        TranscribeTask(context, book, title, source, extension, language)
    }

    private fun save(dur: Double, covered: Double, complete: Boolean) {
        Library.writeAtomic(
            File(book, "cap01.json"),
            JSONObject().put("title", title).put("duration", round3(dur)).put("cues", cueList).toString(),
        )
        val entry = JSONObject().put("id", 1).put("base", "cap01").put("title", title).put("n", cueList.length())
            .put("duration", round3(dur)).put("done", complete).put("file", fileName)
        Library.writeAtomic(File(book, "manifest.json"), JSONArray().put(entry).toString())
        Library.writeAtomic(
            File(book, "gen_state.json"),
            JSONObject().put("total", 1).put("complete", complete).put("kind", "import")
                .put("covered", round3(covered)).put("dur", round3(dur)).put("language", language ?: "auto").toString(),
        )
    }

    override suspend fun run(ctl: Control) {
        val api = ctl.watch(ModelApi(Prefs.get(context).endpoint(Capability.TRANSCRIPTION)))
        var covered = 0.0
        if (resume) {
            val st = JSONObject(File(book, "gen_state.json").readText())
            covered = st.optDouble("covered", 0.0)
            language = st.optString("language").ifEmpty { null }
            val m = JSONArray(File(book, "manifest.json").readText()).getJSONObject(0)
            fileName = m.optString("file").ifEmpty { fileName }
            val old = JSONObject(File(book, "cap01.json").readText()).optJSONArray("cues") ?: JSONArray()
            for (i in 0 until old.length()) cueList.put(old.get(i))
        } else {
            ctl.progress { it.copy(stage = Stage.COPYING) }
            val dest = File(book, fileName)
            val input = context.contentResolver.openInputStream(source!!) ?: throw IOException("can't read")
            input.use { src ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        ctl.check()
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
        val audio = File(book, fileName)
        val dur = AudioCodec.durationOf(audio).takeIf { it > 0 } ?: throw IOException("no duration")
        save(dur, covered, false)
        Work.changed.value = Work.changed.value + 1
        ctl.progress { it.copy(stage = Stage.TRANSCRIBING, pct = (covered / dur * 100).toInt(), durMs = (dur * 1000).toLong()) }
        val chunker = Chunker(RATE, CHUNK_SECONDS)
        val wav = File(context.cacheDir, "transcribe-${book.name}.wav")
        var failure: Exception? = null
        var position = covered

        suspend fun send(samples: ShortArray, count: Int) {
            AudioCodec.writeWav(wav, samples, count, RATE)
            val seconds = count.toDouble() / RATE
            val t = ctl.retrying { api.transcribe(wav, language) }
            val segs = Timing.segments(t, seconds)
            for (s in segs) {
                cueList.put(cueJson(round3(position + s.start), round3(position + s.end), cueList.length(), s.text))
            }
            position += seconds
            save(dur, position, false)
            ctl.progress {
                it.copy(
                    stage = Stage.TRANSCRIBING, pct = (position / dur * 100).toInt().coerceAtMost(99),
                    posMs = (position * 1000).toLong(), durMs = (dur * 1000).toLong(),
                )
            }
        }

        // The decoder calls back on this thread, and it can't suspend: the pieces wait here.
        val ready = ArrayDeque<Pair<ShortArray, Int>>()
        var decoderDone = false
        val decoder = Thread {
            try {
                AudioCodec.decode(audio, covered) { piece, rate ->
                    if (ctl.mode != Control.Mode.RUN) return@decode false
                    val pcm = Pcm(piece, rate).resampledTo(RATE)
                    chunker.add(pcm.samples) { out, n ->
                        val copy = out.copyOf(n)
                        synchronized(ready) {
                            ready.addLast(copy to n)
                            (ready as Object).notifyAll()
                            while (ready.size >= 2 && ctl.mode == Control.Mode.RUN) (ready as Object).wait(500)
                        }
                    }
                    true
                }
                chunker.finish { out, n ->
                    val copy = out.copyOf(n)
                    synchronized(ready) {
                        ready.addLast(copy to n)
                        (ready as Object).notifyAll()
                    }
                }
            } catch (e: Exception) {
                failure = e
            } finally {
                synchronized(ready) {
                    decoderDone = true
                    (ready as Object).notifyAll()
                }
            }
        }
        decoder.start()
        try {
            while (true) {
                ctl.check()
                val next = synchronized(ready) {
                    while (ready.isEmpty() && !decoderDone && ctl.mode == Control.Mode.RUN) (ready as Object).wait(500)
                    val n = ready.removeFirstOrNull()
                    (ready as Object).notifyAll()
                    n
                }
                if (next == null) {
                    ctl.check()
                    if (decoderDone) break else continue
                }
                send(next.first, next.second)
            }
        } finally {
            synchronized(ready) { (ready as Object).notifyAll() }
            wav.delete()
        }
        failure?.let { throw IOException(it) }
        save(dur, dur, true)
    }

    private companion object {
        const val RATE = 16_000
        const val CHUNK_SECONDS = 120
    }
}

/** Translates a document already copied to its folder. */
class TranslateTask(
    private val context: Context,
    private val folder: File,
    private val meta: JSONObject,
) : Task("translate", meta.getString("name"), translationId = folder.name) {
    private val resume = File(folder, "cache.json").exists()

    override fun discard() {
        if (!resume) folder.deleteRecursively()
    }

    override fun resumed() = TranslateTask(context, folder, meta)

    private fun saveMeta() = Library.writeAtomic(File(folder, "meta.json"), meta.toString())

    override suspend fun run(ctl: Control) {
        val endpoint = Prefs.get(context).endpoint(Capability.TRANSLATION)
        val api = ctl.watch(ModelApi(endpoint))
        ctl.progress { it.copy(stage = Stage.READING) }
        val src = meta.getString("src")
        val tgt = meta.getString("tgt")
        val doc = try {
            PDFBoxResourceLoader.init(context)
            Docs.open(File(folder, meta.getString("source")), tgt, meta.getString("name").substringBeforeLast('.'), ctl::check)
        } catch (e: Stopped) {
            throw e
        } catch (_: Exception) {
            discard()
            throw BadDocument("cant_read_file")     // "zip END header not found" helps nobody
        }
        val texts = doc.units.map { it.text }.distinct().filter(Translation::needsTranslation)
        if (texts.isEmpty()) {
            discard()
            throw BadDocument(if (doc is PdfDoc) "no_text_pdf" else "no_text")
        }
        val cacheFile = File(folder, "cache.json")
        val cache = try {
            JSONObject(cacheFile.readText())
        } catch (_: Exception) {
            JSONObject()
        }
        meta.put("total", texts.size).put("state", "partial")
        saveMeta()
        fun key(t: String) = Translation.key(t)
        val todo = texts.filter { !cache.has(key(t = it)) }
        var done = texts.size - todo.size
        val output = File(folder, meta.getString("output"))
        val pace = ArrayDeque<Long>()
        val parallel = endpoint.parallel.coerceAtLeast(1)
        var lastSave = System.currentTimeMillis()
        ctl.progress { it.copy(stage = Stage.TRANSLATING, pct = done * 100 / texts.size, a = done, b = texts.size) }

        fun write() {
            ctl.progress { it.copy(stage = Stage.SAVING) }
            Library.writeAtomic(cacheFile, cache.toString())
            for (u in doc.units) u.apply(cache.optString(key(u.text), u.text).ifEmpty { u.text })
            doc.save(output)
        }

        try {
            var i = 0
            while (i < todo.size) {
                ctl.check()
                val window = todo.subList(i, minOf(i + parallel, todo.size))
                val out = coroutineScope {
                    window.map { text ->
                        async(Dispatchers.IO) { translateOne(ctl, api, text, src, tgt) }
                    }.awaitAll()
                }
                for ((k, t) in window.withIndex()) cache.put(key(t), out[k])
                i += window.size
                done += window.size
                val now = System.nanoTime() / 1_000_000
                repeat(window.size) { pace.addLast(now) }
                while (pace.size > 30) pace.removeFirst()
                val per = if (pace.size >= 4) (pace.last() - pace.first()).toDouble() / (pace.size - 1) / 1000 else null
                ctl.progress {
                    it.copy(
                        stage = Stage.TRANSLATING, pct = done * 100 / texts.size, a = done, b = texts.size,
                        etaSec = per?.let { p -> p * (texts.size - done) },
                    )
                }
                if (System.currentTimeMillis() - lastSave > 8_000) {
                    lastSave = System.currentTimeMillis()
                    Library.writeAtomic(cacheFile, cache.toString())
                }
            }
        } catch (e: Stopped) {
            // Stopping leaves a document with what is translated so far; the rest stays in the original.
            if (ctl.mode == Control.Mode.SAVE) write()
            throw e
        } catch (e: IOException) {
            write()
            throw e
        }
        write()
        meta.put("state", "done")
        saveMeta()
        cacheFile.delete()
    }

    private suspend fun translateOne(ctl: Control, api: ModelApi, text: String, src: String, tgt: String): String {
        val parts = ArrayList<String>()
        for (chunk in Translation.splitLong(text)) {
            val prompt = Translation.prompt(chunk, src, tgt)
            val limit = minOf(4096, (chunk.length * 1.5).toInt() + 128)
            var out = ctl.retrying { api.chat(prompt, 0.0, limit) }.trim()
            if (Translation.looksBroken(chunk, out)) {
                val again = ctl.retrying { api.chat(prompt, 0.4, limit) }.trim()
                out = Translation.settle(chunk, again)
            }
            parts += out
        }
        return parts.joinToString(" ")
    }
}

/** Spells numbers with the phone's own data, in any language. */
class Speller(lang: String) {
    private val rules = runCatching {
        android.icu.text.MessageFormat("{0,spellout}", android.icu.util.ULocale.forLanguageTag(lang))
    }.getOrNull()

    fun spell(n: Long): String = runCatching { rules?.format(arrayOf<Any>(n)) }.getOrNull() ?: n.toString()
}
