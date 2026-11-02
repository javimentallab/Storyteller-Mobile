package com.javimetallab.storyteller.data

import org.json.JSONArray
import org.json.JSONObject

data class BookSummary(
    val id: String,
    val title: String,
    val kind: String,
    val chapters: Int,
    val total: Int,
    val done: Int,
    val duration: Double,
    val complete: Boolean,
    val resumable: Boolean,
    val progress: Int,
    val hasSource: Boolean,
)

data class Chapter(
    val id: Int,
    val base: String,
    val title: String,
    val n: Int,
    val duration: Double,
    val done: Boolean,
    val partial: Boolean,
    val frames: Long,
    val sr: Int,
    val file: String,
)

data class BookDetail(val summary: BookSummary, val chapters: List<Chapter>)

data class Cue(val start: Double, val end: Double, val text: String)

data class ChapterCues(val title: String, val cues: List<Cue>)

data class Bookmark(val cap: Int, val base: String?, val pos: Long, val updated: Double)

/** The three things that need a model somewhere else. Each one has its own URL. */
enum class Capability(val key: String) {
    NARRATION("narration"),
    TRANSCRIPTION("transcription"),
    TRANSLATION("translation"),
}

data class Endpoint(
    val url: String = "",
    val key: String = "",
    val model: String = "",
    val voice: String = "",
    val format: String = "wav",
    val parallel: Int = 1,
    val language: String = "",
    val qc: Boolean = false,
) {
    /** Enough to try. Some services need neither key nor model (a Whisper on the home network). */
    val isSet get() = url.isNotBlank()
}

data class TranslationItem(
    val id: String,
    val name: String,
    val src: String,
    val tgt: String,
    val state: String,
    val done: Int,
    val total: Int,
    val outputName: String,
    val outputSize: Long,
)

/** A JSON null reads as "null" with optString: this gives a real null. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

private inline fun <T> JSONArray?.mapObjects(block: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).map { block(getJSONObject(it)) }
}

object Parse {
    fun cues(o: JSONObject) = ChapterCues(
        title = o.optString("title"),
        cues = o.optJSONArray("cues").mapObjects {
            Cue(it.optDouble("start", 0.0), it.optDouble("end", 0.0), it.optString("text"))
        },
    )

    fun bookmark(o: JSONObject) = Bookmark(
        cap = o.optInt("cap"),
        base = o.str("base"),
        pos = o.optLong("pos"),
        updated = o.optDouble("updated", 0.0),
    )

    /** One entry of manifest.json, the same format as the desktop app. */
    fun chapter(o: JSONObject, imported: Boolean): Chapter {
        val base = o.getString("base")
        val done = o.optBoolean("done", true)
        val partial = !done && !imported
        return Chapter(
            id = o.optInt("id"),
            base = base,
            title = o.optString("title"),
            n = o.optInt("n"),
            duration = o.optDouble("duration", 0.0),
            done = done,
            partial = partial,
            frames = o.optLong("frames"),
            sr = o.optInt("sr"),
            file = o.str("file") ?: if (partial) "$base.wav" else "$base.mp3",
        )
    }

    fun chapters(a: JSONArray?, imported: Boolean) = a.mapObjects { chapter(it, imported) }
}
