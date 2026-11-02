package com.javimetallab.storyteller.data

import android.content.Context
import com.javimetallab.storyteller.engine.Pron
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The user's pronunciation dictionary. Same pron.json as the desktop app: the top of it is for
 * Spanish and any other language goes in "langs", because what is written so that it sounds right
 * in Spanish sounds wrong in the rest.
 */
object PronStore {
    private val SEED = JSONObject()
        .put(
            "pron",
            JSONObject().put("software", "sófwer").put("hardware", "járgüer").put("email", "ímeil").put("online", "onláin")
                .put("streaming", "estrímin").put("podcast", "pódcast").put("marketing", "márketin")
                .put("feedback", "fídbak").put("meeting", "mítin"),
        )
        .put(
            "say_letters",
            JSONArray(listOf("SQL", "HTML", "CSS", "PDF", "URL", "USB", "CPU", "GPU", "HTTP", "API", "FBI", "CIA", "DVD", "USA")),
        )
        .put("say_word", JSONArray())

    private fun file(context: Context) = File(context.filesDir, "pron.json")

    fun read(context: Context): JSONObject = try {
        JSONObject(file(context).readText(Charsets.UTF_8))
    } catch (_: Exception) {
        JSONObject(SEED.toString())
    }

    fun forLanguage(context: Context, lang: String) = Pron.of(read(context), lang)

    /** What the user sees: "word = how it is said" on each line, and the acronyms separated by commas. */
    data class Texts(val words: String, val letters: String, val asWords: String)

    fun texts(root: JSONObject, lang: String): Texts {
        val e = Pron.entry(root, lang) ?: JSONObject()
        val pron = e.optJSONObject("pron") ?: JSONObject()
        val words = pron.keys().asSequence().sorted().joinToString("\n") { "$it = ${pron.optString(it)}" }
        fun list(key: String) = e.optJSONArray(key)?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
        return Texts(words, list("say_letters"), list("say_word"))
    }

    fun withTexts(root: JSONObject, lang: String, t: Texts): JSONObject {
        val pron = JSONObject()
        for (line in t.words.lines()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val word = line.substring(0, eq).trim()
            val said = line.substring(eq + 1).trim()
            if (word.isNotEmpty() && said.isNotEmpty()) pron.put(word, said)
        }
        fun list(s: String) = JSONArray(s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() })
        val e = JSONObject().put("pron", pron).put("say_letters", list(t.letters)).put("say_word", list(t.asWords))
        if (lang == "es") {
            for (k in e.keys()) root.put(k, e.get(k))
        } else {
            val langs = root.optJSONObject("langs") ?: JSONObject().also { root.put("langs", it) }
            langs.put(lang, e)
        }
        return root
    }

    fun save(context: Context, root: JSONObject) = Library.writeAtomic(file(context), root.toString(2))
}
