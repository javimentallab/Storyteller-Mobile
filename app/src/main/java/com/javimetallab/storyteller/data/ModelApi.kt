package com.javimetallab.storyteller.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID

/** The service answered, and what it said was no. */
class ModelException(
    val kind: Kind,
    val status: Int,
    val detail: String?,
    val retryAfterMs: Long? = null,
) : IOException("$kind $status $detail") {
    enum class Kind { AUTH, NOT_FOUND, RATE_LIMIT, BAD_REQUEST, SERVER, BAD_ANSWER }

    /** Worth trying again by itself: the service is busy, not refusing. */
    val retriable get() = kind == Kind.RATE_LIMIT || kind == Kind.SERVER
}

data class Segment(val start: Double, val end: Double, val text: String)

data class Word(val start: Double, val end: Double, val text: String)

data class Transcript(val text: String, val segments: List<Segment>, val words: List<Word> = emptyList())

/** A book on the user's own server, st_server: only its finished chapters. */
data class RemoteBook(val id: String, val title: String, val manifest: JSONArray, val state: JSONObject, val files: List<RemoteFile>) {
    val size get() = files.sumOf { it.size }
    val total get() = state.optInt("total", manifest.length())
}

data class RemoteFile(val name: String, val size: Long)

/**
 * Client of the de facto standard API that OpenAI, Groq, Ollama, llama.cpp, Whisper servers and
 * many others speak. All calls block: use them from a background thread.
 */
class ModelApi(private val endpoint: Endpoint) {
    private val base = Format.apiBase(endpoint.url) ?: throw ModelException(ModelException.Kind.BAD_REQUEST, 0, "url")
    private val open = Collections.synchronizedSet(HashSet<HttpURLConnection>())

    /** Cuts whatever is in flight: the blocked call ends with an exception. */
    fun cancel() {
        val now = synchronized(open) { open.toList() }
        now.forEach { runCatching { it.disconnect() } }
    }

    private fun connect(path: String, method: String, readTimeout: Int): HttpURLConnection {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = readTimeout
        c.instanceFollowRedirects = false       // the key must not follow a redirect somewhere else
        if (endpoint.key.isNotBlank() && Format.mayCarryKey(base)) c.setRequestProperty("Authorization", "Bearer ${endpoint.key.trim()}")
        c.setRequestProperty("User-Agent", "Storyteller")
        open.add(c)
        return c
    }

    private fun failure(c: HttpURLConnection): ModelException {
        val status = c.responseCode
        val body = try {
            c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (_: IOException) {
            null
        }
        val kind = when (status) {
            401, 403 -> ModelException.Kind.AUTH
            404 -> ModelException.Kind.NOT_FOUND
            429 -> ModelException.Kind.RATE_LIMIT
            in 500..599 -> ModelException.Kind.SERVER
            else -> ModelException.Kind.BAD_REQUEST
        }
        val retry = c.getHeaderField("Retry-After")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        return ModelException(kind, status, errorMessage(body), retry)
    }

    private inline fun <T> use(c: HttpURLConnection, block: () -> T): T {
        try {
            return block()
        } finally {
            open.remove(c)
            c.disconnect()
        }
    }

    private fun getJson(path: String): JSONObject {
        val c = connect(path, "GET", 30_000)
        return use(c) {
            if (c.responseCode !in 200..299) throw failure(c)
            parseJson(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        }
    }

    private fun postJson(path: String, body: JSONObject, readTimeout: Int, sink: (HttpURLConnection) -> Unit) {
        val c = connect(path, "POST", readTimeout)
        use(c) {
            val data = body.toString().toByteArray(Charsets.UTF_8)
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(data.size)
            c.outputStream.use { it.write(data) }
            if (c.responseCode !in 200..299) throw failure(c)
            sink(c)
        }
    }

    /** The ids of GET /models. Not every service has it. */
    fun models(): List<String> {
        val data = getJson("/models").optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.ifEmpty { null } }.sorted()
    }

    /** Voices, on the services that list them. Most standard ones don't: then the name is typed. */
    fun voices(): List<String> {
        val o = getJson("/audio/voices")
        return parseVoices(o.optJSONArray("voices") ?: o.optJSONArray("data"))
    }

    @Volatile private var format = endpoint.format.ifBlank { "wav" }

    @Volatile private var saysLanguage = true

    /**
     * Narrates one sentence into a file. Without loss if the service can: what a voice loses here
     * doesn't come back. The standard API has no language, so it goes along until the service
     * refuses it, and from then on the service gets it from the text. Like cc_service on the desktop.
     */
    fun speech(text: String, dest: File, language: String? = null) {
        val body = JSONObject().put("input", text)
        if (endpoint.model.isNotBlank()) body.put("model", endpoint.model)
        if (endpoint.voice.isNotBlank()) body.put("voice", endpoint.voice)
        if (saysLanguage && !language.isNullOrBlank()) {
            try {
                return speechInSomeFormat(JSONObject(body.toString()).put("language", language), dest)
            } catch (e: ModelException) {
                if (e.kind != ModelException.Kind.BAD_REQUEST) throw e
            }
            saysLanguage = false
        }
        speechInSomeFormat(body, dest)
    }

    private fun speechInSomeFormat(body: JSONObject, dest: File) {
        fun ask(asked: String) = postJson("/audio/speech", JSONObject(body.toString()).put("response_format", asked), 180_000) { c ->
            c.inputStream.use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
        }
        try {
            ask(format)
        } catch (e: ModelException) {
            if (e.kind != ModelException.Kind.BAD_REQUEST || format == "mp3") throw e
            ask("mp3")
            format = "mp3"
        }
    }

    /** An audio file becomes a voice, on the user's own server. Other services answer 404. */
    fun uploadVoice(name: String, audio: File) {
        val c = connect("/audio/voices/${encode(name)}", "PUT", 180_000)
        use(c) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(audio.length())
            c.outputStream.use { out -> audio.inputStream().use { it.copyTo(out) } }
            if (c.responseCode !in 200..299) throw failure(c)
        }
    }

    /** The books of the user's own server. Other services answer 404. */
    fun books(): List<RemoteBook> = parseBooks(getJson("/storyteller/books").optJSONArray("books"))

    /** One file of one of those books, reporting how much has arrived. */
    fun download(book: String, name: String, dest: File, onBytes: (Long) -> Unit = {}) {
        val c = connect("/storyteller/books/${encode(book)}/${encode(name)}", "GET", 120_000)
        use(c) {
            if (c.responseCode !in 200..299) throw failure(c)
            c.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        got += n
                        onBytes(got)
                    }
                }
            }
        }
    }

    /** Which of [ASKS] this service answered: the rest of the book doesn't upload each piece again. */
    @Volatile private var ask = 0

    /**
     * Transcribes one piece of audio. Asks for times per word, the only ones that follow the voice
     * closely. A model that can't give them refuses with a 400, and then it asks for less. Only an
     * answer changes what is asked from then on: a piece that fails everywhere says nothing of the service.
     */
    fun transcribe(wav: File, language: String?, prompt: String? = null): Transcript {
        for (i in ask..ASKS.lastIndex) {
            try {
                val answer = parseTranscript(sendAudio(wav, language, ASKS[i].first, ASKS[i].second, prompt))
                ask = i
                return answer
            } catch (e: ModelException) {
                if (e.kind != ModelException.Kind.BAD_REQUEST || i == ASKS.lastIndex) throw e
            }
        }
        error("unreachable")
    }

    private fun sendAudio(wav: File, language: String?, format: String, timesOf: List<String>, prompt: String?): JSONObject {
        val boundary = "----storyteller" + UUID.randomUUID().toString().replace("-", "")
        val fields = buildString {
            fun field(name: String, value: String) {
                append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            if (endpoint.model.isNotBlank()) field("model", endpoint.model)
            field("response_format", format)
            for (t in timesOf) field("timestamp_granularities[]", t)
            if (!language.isNullOrBlank() && language != "auto") field("language", language)
            if (!prompt.isNullOrBlank()) field("prompt", prompt)
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
            append("Content-Type: audio/wav\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val c = connect("/audio/transcriptions", "POST", 600_000)
        return use(c) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            c.setFixedLengthStreamingMode(fields.size + wav.length() + tail.size)
            c.outputStream.use { out ->
                out.write(fields)
                wav.inputStream().use { it.copyTo(out) }
                out.write(tail)
            }
            if (c.responseCode !in 200..299) throw failure(c)
            parseJson(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
        }
    }

    /** One user message, one answer. Some models refuse parameters others need: it adapts. */
    fun chat(prompt: String, temperature: Double, maxTokens: Int): String {
        var useTemperature = true
        var tokensField = "max_tokens"
        repeat(3) {
            val body = JSONObject()
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
                .put(tokensField, maxTokens)
            if (endpoint.model.isNotBlank()) body.put("model", endpoint.model)
            if (useTemperature) body.put("temperature", temperature)
            try {
                var out: String? = null
                postJson("/chat/completions", body, 600_000) { c ->
                    out = parseChat(parseJson(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }))
                }
                return out.orEmpty()
            } catch (e: ModelException) {
                val says = e.detail.orEmpty().lowercase()
                when {
                    e.kind != ModelException.Kind.BAD_REQUEST -> throw e
                    tokensField == "max_tokens" && "max_tokens" in says -> tokensField = "max_completion_tokens"
                    useTemperature && "temperature" in says -> useTemperature = false
                    else -> throw e
                }
            }
        }
        throw ModelException(ModelException.Kind.BAD_REQUEST, 400, "parameters")
    }

    companion object {
        private val BOOK_FILE = Regex("cap\\d{2,3}\\.(?:mp3|m4a|json)|source\\.epub")
        private val CHAPTER_BASE = Regex("cap\\d{2,3}")

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** What a server says becomes file names on the phone: only what a book is allowed to have. */
        fun parseBooks(a: JSONArray?): List<RemoteBook> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").ifEmpty { return@mapNotNull null }
                val manifest = JSONArray()
                val listed = o.optJSONArray("manifest")
                for (k in 0 until (listed?.length() ?: 0)) {
                    val e = listed!!.optJSONObject(k) ?: continue
                    if (!CHAPTER_BASE.matches(e.optString("base"))) continue
                    e.remove("file")
                    manifest.put(e)
                }
                if (manifest.length() == 0) return@mapNotNull null
                val f = o.optJSONArray("files")
                val files = (0 until (f?.length() ?: 0)).mapNotNull { k ->
                    val x = f!!.optJSONObject(k) ?: return@mapNotNull null
                    val name = x.optString("name")
                    if (BOOK_FILE.matches(name)) RemoteFile(name, x.optLong("size")) else null
                }
                RemoteBook(id, o.optString("title").ifEmpty { id }, manifest, o.optJSONObject("state") ?: JSONObject(), files)
            }
        }

        private val ASKS = listOf(
            "verbose_json" to listOf("word", "segment"),
            "verbose_json" to emptyList(),
            "json" to emptyList(),
        )

        private fun parseJson(text: String): JSONObject = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ModelException(ModelException.Kind.BAD_ANSWER, 200, text.take(200))
        }

        /** {"error": {"message": …}}, {"error": "…"}, {"detail": "…"}, {"message": "…"}, or just text. */
        fun errorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            val o = try {
                JSONObject(body)
            } catch (_: Exception) {
                return body.take(300)
            }
            val err = o.opt("error")
            if (err is JSONObject) err.str("message")?.let { return it.take(300) }
            if (err is String) return err.take(300)
            val detail = o.opt("detail")
            if (detail != null && detail != JSONObject.NULL) return detail.toString().take(300)
            return o.str("message")?.take(300) ?: body.take(300)
        }

        fun parseTranscript(o: JSONObject): Transcript {
            val a = o.optJSONArray("segments")
            val segs = ArrayList<Segment>()
            val nested = ArrayList<Word>()               // some servers keep the words inside each segment
            if (a != null) {
                for (i in 0 until a.length()) {
                    val s = a.optJSONObject(i) ?: continue
                    val text = s.optString("text").trim()
                    if (text.isNotEmpty()) segs += Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), text)
                    nested += parseWords(s.optJSONArray("words"))
                }
            }
            return Transcript(o.optString("text").trim(), segs, parseWords(o.optJSONArray("words")).ifEmpty { nested })
        }

        private fun parseWords(a: JSONArray?): List<Word> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val w = a.optJSONObject(i) ?: return@mapNotNull null
                val text = (w.str("word") ?: w.str("text")).orEmpty().trim()
                if (text.isEmpty()) null else Word(w.optDouble("start", 0.0), w.optDouble("end", 0.0), text)
            }
        }

        fun parseChat(o: JSONObject): String {
            val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw ModelException(ModelException.Kind.BAD_ANSWER, 200, o.toString().take(200))
            return msg.optString("content").trim()
        }

        /** Plain names, or objects with an id or a name: services don't agree. */
        fun parseVoices(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                when (val v = a.opt(i)) {
                    is String -> v
                    is JSONObject -> (v.str("id") ?: v.str("voice_id") ?: v.str("name"))
                    else -> null
                }
            }.filter { it.isNotBlank() }.sorted()
        }
    }
}
