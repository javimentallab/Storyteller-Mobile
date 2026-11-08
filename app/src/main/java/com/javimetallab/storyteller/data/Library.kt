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

    /**
     * The folder for a book brought from the computer: its own (origin.json says which server book it is),
     * never one of this phone's. Before, a phone book with the same name got the fetched one mixed into it.
     */
    @Synchronized
    fun remoteFolder(remoteId: String): String {
        val s = Format.slug(remoteId)
        var name = s
        var n = 1
        while (true) {
            val d = File(root, name)
            if (!d.exists() || cameFrom(d, remoteId)) return name
            n++
            name = "$s-$n"
        }
    }

    private fun cameFrom(d: File, remoteId: String): Boolean {
        val origin = File(d, ORIGIN)
        if (origin.exists()) {
            return try {
                JSONObject(origin.readText(Charsets.UTF_8)).optString("server_id") == remoteId
            } catch (_: Exception) {
                false
            }
        }
        // Fetched before origin.json existed: what this phone makes always has options.json (narrating) or the
        // language in its state (transcribing); the computer's books have neither.
        return !File(d, "options.json").exists() && state(d)?.has("language") != true
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

    /**
     * Regenerating: the book as it was goes aside, out of the library, and comes back if the new
     * narration is cancelled or fails before narrating anything. It was deleted before starting, and a
     * cancelled regeneration deleted the whole folder, the EPUB included.
     */
    fun setAside(id: String): File {
        val d = dir(id)
        val backup = File(File(root.parentFile, "regen"), id)
        backup.deleteRecursively()                       // left from a regeneration cut off by a crash
        backup.parentFile?.mkdirs()
        if (!d.renameTo(backup)) {
            d.copyRecursively(backup, overwrite = true)
            d.deleteRecursively()
        }
        return backup
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

        /** In a book brought from the computer: which book of the server it is. */
        const val ORIGIN = "origin.json"

        /**
         * A book's index, to resume it. If writing it was cut off between removing the old one and
         * renaming the new one, the new one is still in the .tmp. Null if there is none at all; if there
         * is one but it can't be read, an error: carrying on would save an empty index over it and
         * narrate the whole book again.
         */
        fun readIndex(book: File): JSONArray? {
            val main = File(book, "manifest.json")
            val tmp = File(main.path + ".tmp")
            for (f in listOf(main, tmp)) {
                if (!f.exists()) continue
                try {
                    return JSONArray(f.readText(Charsets.UTF_8))
                } catch (_: Exception) {
                }
            }
            if (main.exists() || tmp.exists()) throw java.io.IOException("unreadable manifest.json")
            return null
        }

        /**
         * The index after bringing a book from the computer: what arrived, plus the chapters that only
         * this phone has (narrated here after an earlier fetch). Replacing it dropped those from the index,
         * and resuming narrated them again.
         */
        fun mergeIndex(local: JSONArray?, remote: JSONArray): JSONArray {
            val byId = sortedMapOf<Int, JSONObject>()
            for (a in listOfNotNull(local, remote)) {
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    if (e.has("id")) byId[e.optInt("id")] = e
                }
            }
            return JSONArray().also { out -> byId.values.forEach { out.put(it) } }
        }

        /** Puts back a book set aside with [setAside], over whatever the regeneration left. */
        fun restore(backup: File, dest: File) {
            if (!backup.exists()) return
            dest.deleteRecursively()
            if (!backup.renameTo(dest)) {
                backup.copyRecursively(dest, overwrite = true)
                backup.deleteRecursively()
            }
        }

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
