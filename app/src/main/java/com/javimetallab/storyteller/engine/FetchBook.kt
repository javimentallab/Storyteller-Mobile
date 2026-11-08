package com.javimetallab.storyteller.engine

import android.content.Context
import com.javimetallab.storyteller.data.Capability
import com.javimetallab.storyteller.data.Library
import com.javimetallab.storyteller.data.ModelApi
import com.javimetallab.storyteller.data.Prefs
import com.javimetallab.storyteller.data.RemoteBook
import org.json.JSONObject
import java.io.File

/**
 * Brings a book already narrated on the desktop, from st_server. What has already arrived isn't
 * asked for again, and the book only appears when all of it is here.
 */
class FetchBookTask(private val context: Context, private val remote: RemoteBook, private val book: File) :
    Task("fetch", remote.title, bookId = book.name) {
    override fun discard() {
        if (!File(book, "manifest.json").exists()) book.deleteRecursively()
    }

    override suspend fun run(ctl: Control) {
        val api = ctl.watch(ModelApi(Prefs.get(context).endpoint(Capability.NARRATION)))
        book.mkdirs()
        Library.writeAtomic(File(book, Library.ORIGIN), JSONObject().put("server_id", remote.id).toString())
        val total = remote.size.coerceAtLeast(1)
        var done = 0L
        ctl.progress { it.copy(stage = Stage.COPYING, pct = 0) }
        for (f in remote.files) {
            val dest = File(book, f.name)
            if (dest.length() != f.size) {
                val part = File(book, f.name + ".part")
                val before = done
                ctl.retrying {
                    api.download(remote.id, f.name, part) { got ->
                        ctl.check()
                        val pct = ((before + got) * 100 / total).toInt().coerceAtMost(99)
                        ctl.progress { it.copy(stage = Stage.COPYING, pct = pct) }
                    }
                }
                if (part.length() != f.size) throw java.io.IOException("short ${f.name}")
                dest.delete()
                if (!part.renameTo(dest)) throw java.io.IOException("rename ${f.name}")
            }
            done += f.size
        }
        Library.writeAtomic(File(book, "title.txt"), remote.title)
        // What arrived wins chapter by chapter; what only this phone has stays.
        val local = try {
            Library.readIndex(book)
        } catch (_: java.io.IOException) {
            null                                    // unreadable here: what arrived is all there is
        }
        val index = Library.mergeIndex(local, remote.manifest)
        val doneChapters = (0 until index.length()).count { index.optJSONObject(it)?.optBoolean("done") == true }
        val state = JSONObject(remote.state.toString())
        if (!state.has("total")) state.put("total", remote.total)
        val chapterTotal = state.optInt("total", remote.total)
        state.put("complete", state.optBoolean("complete") || (chapterTotal > 0 && doneChapters >= chapterTotal))
        Library.writeAtomic(File(book, "gen_state.json"), state.toString())
        Library.writeAtomic(File(book, "manifest.json"), index.toString())
        Work.changed.value = Work.changed.value + 1
    }
}
