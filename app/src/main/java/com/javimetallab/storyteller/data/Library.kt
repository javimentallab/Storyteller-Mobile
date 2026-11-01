package com.javimetallab.storyteller.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The books, on the phone. Same layout as the desktop app: manifest.json, gen_state.json,
 * capNN.json with the subtitles and the audio of each chapter.
 */
class Library(context: Context) {
    private val app = context.applicationContext
    val root: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "books").also { it.mkdirs() }

    fun dir(id: String): File {
        require(ID.matches(id)) { "bad book id" }
        return File(root, id)
    }

    /** A new folder for a title. Never reuses one: nothing gets overwritten. */
    @Synchronized
    fun newBook(title: String): File {
        val s = Format.slug(title)
        var d = File(root, s)
        var n = 1
        while (d.exists()) {
            n++
            d = File(root, "$s-$n")
        }
        d.mkdirs()
        writeAtomic(File(d, "title.txt"), title)
        return d
    }

    fun title(d: File): String = try {
        File(d, "title.txt").readText(Charsets.UTF_8).trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    } ?: d.name.replace('-', ' ').replaceFirstChar { it.uppercase() }

    private fun state(d: File): JSONObject? = try {
        JSONObject(File(d, "gen_state.json").readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    private fun manifest(d: File): JSONArray? = try {
        JSONArray(File(d, "manifest.json").readText(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    fun detail(id: String): BookDetail? {
        val d = dir(id)
        val st = state(d)
        val m = manifest(d)
        if (m == null && st == null) return null
        val imported = st?.optString("kind") == "import"
        val chapters = Parse.chapters(m, imported)
        val complete = st?.optBoolean("complete", true) ?: true
        val total = st?.optInt("total", chapters.size)?.takeIf { it > 0 } ?: chapters.size
        val done = chapters.count { it.done }
        val dur = st?.optDouble("dur", 0.0) ?: 0.0
        val sentences = st?.optInt("sentences") ?: 0
        val progress = when {
            complete -> 100
            imported && dur > 0 -> (st!!.optDouble("covered", 0.0) / dur * 100).toInt().coerceIn(0, 100)
            sentences > 0 -> (chapters.sumOf { it.n } * 100 / sentences).coerceIn(0, 99)
            total > 0 -> done * 100 / total
            else -> 0
        }
        val hasSource = File(d, "source.epub").exists()
        val resumable = !complete && if (imported) chapters.firstOrNull()?.let { File(d, it.file).exists() } == true else hasSource
        return BookDetail(
            BookSummary(
                id = id, title = title(d), kind = if (imported) "import" else "narrate",
                chapters = chapters.size, total = total, done = done,
                duration = chapters.sumOf { it.duration }, complete = complete,
                resumable = resumable, progress = progress, hasSource = hasSource,
            ),
            chapters,
        )
    }

    fun list(): List<BookSummary> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && ID.matches(it.name) }
            .mapNotNull { detail(it.name)?.summary }
            .sortedBy { it.title.lowercase() }

    fun audioFile(id: String, ch: Chapter) = File(dir(id), ch.file)

    fun cues(id: String, base: String): ChapterCues? = try {
        Parse.cues(JSONObject(File(dir(id), "$base.json").readText(Charsets.UTF_8)))
    } catch (_: Exception) {
        null
    }

    fun delete(id: String) {
        dir(id).deleteRecursively()
    }

    // ---- translations
    val translationsRoot: File = File(app.getExternalFilesDir(null) ?: app.filesDir, "translations").also { it.mkdirs() }

    fun translations(): List<TranslationItem> =
        translationsRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { f ->
            try {
                val m = JSONObject(File(f, "meta.json").readText(Charsets.UTF_8))
                val out = File(f, m.getString("output"))
                val cache = File(f, "cache.json")
                val done = if (cache.exists()) JSONObject(cache.readText(Charsets.UTF_8)).length() else m.optInt("total")
                TranslationItem(
                    id = f.name, name = m.getString("name"), src = m.getString("src"), tgt = m.getString("tgt"),
                    state = m.optString("state", "partial"), done = done, total = m.optInt("total"),
                    outputName = m.getString("output_name"), outputSize = if (out.exists()) out.length() else 0,
                ) to f.lastModified()
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.second }.map { it.first }

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,200}")

        fun writeAtomic(f: File, text: String) {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) throw java.io.IOException("rename ${f.name}")
            }
        }
    }
}
