package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Endpoint
import com.javimetallab.storyteller.data.ModelApi
import com.javimetallab.storyteller.data.ModelException
import com.javimetallab.storyteller.data.Word
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Against a real HTTP server, with what the services really send. */
class ModelApiTest {
    private lateinit var server: ServerSocket
    private val requests = ArrayList<Captured>()
    private var answer: (Captured) -> Triple<Int, String, Map<String, String>> = { Triple(200, "{}", emptyMap()) }

    class Captured(val path: String, val method: String, val auth: String?, val contentType: String?, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
        val json get() = JSONObject(text)
    }

    @Before
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                socket.use { serve(it) }
            }
        }
    }

    /** One request per connection: a small HTTP server, because the JDK's isn't in reach of Android tests. */
    private fun serve(socket: Socket) {
        val input = socket.getInputStream().buffered()
        fun line(): String {
            val b = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) b.append(c.toChar())
            }
            return b.toString()
        }
        val first = line().split(" ")
        val headers = HashMap<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            headers[h.substringBefore(':').lowercase()] = h.substringAfter(':').trim()
        }
        val body = input.readNBytes(headers["content-length"]?.toInt() ?: 0)
        val c = Captured(first[1], first[0], headers["authorization"], headers["content-type"], body)
        requests += c
        val (status, text, extra) = answer(c)
        val data = text.toByteArray()
        val out = socket.getOutputStream()
        val head = StringBuilder("HTTP/1.1 $status X\r\nConnection: close\r\nContent-Length: ${data.size}\r\n")
        extra.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        out.write(data)
        out.flush()
    }

    @After
    fun stop() = server.close()

    private fun api(model: String = "m", key: String = "sk-test", voice: String = "v", format: String = "mp3") =
        ModelApi(Endpoint(url = "http://127.0.0.1:${server.localPort}/v1", key = key, model = model, voice = voice, format = format))

    @Test
    fun speechSendsWhatTheStandardAsksFor() {
        answer = { Triple(200, "AUDIO", emptyMap()) }
        val f = File.createTempFile("speech", ".audio")
        api().speech("Hola, mundo.", f)
        val r = requests.single()
        assertEquals("/v1/audio/speech", r.path)
        assertEquals("POST", r.method)
        assertEquals("Bearer sk-test", r.auth)
        assertEquals("Hola, mundo.", r.json.getString("input"))
        assertEquals("m", r.json.getString("model"))
        assertEquals("v", r.json.getString("voice"))
        assertEquals("mp3", r.json.getString("response_format"))
        assertArrayEquals("AUDIO".toByteArray(), f.readBytes())
        f.delete()
    }

    @Test
    fun noKeyNoHeader() {
        answer = { Triple(200, "AUDIO", emptyMap()) }
        val f = File.createTempFile("speech", ".audio")
        api(key = "").speech("x", f)
        assertNull(requests.single().auth)
        f.delete()
    }

    @Test
    fun emptyModelAndVoiceAreLeftOut() {
        answer = { Triple(200, "AUDIO", emptyMap()) }
        val f = File.createTempFile("speech", ".audio")
        api(model = "", voice = "").speech("x", f)
        assertFalse(requests.single().json.has("model"))
        assertFalse(requests.single().json.has("voice"))
        f.delete()
    }

    @Test
    fun errorsAreClassified() {
        val cases = listOf(
            Triple(401, ModelException.Kind.AUTH, false),
            Triple(403, ModelException.Kind.AUTH, false),
            Triple(404, ModelException.Kind.NOT_FOUND, false),
            Triple(400, ModelException.Kind.BAD_REQUEST, false),
            Triple(429, ModelException.Kind.RATE_LIMIT, true),
            Triple(500, ModelException.Kind.SERVER, true),
            Triple(503, ModelException.Kind.SERVER, true),
        )
        for ((status, kind, retriable) in cases) {
            answer = { Triple(status, """{"error":{"message":"said $status"}}""", mapOf("Retry-After" to "7")) }
            try {
                api().models()
                fail("no exception for $status")
            } catch (e: ModelException) {
                assertEquals(kind, e.kind)
                assertEquals(retriable, e.retriable)
                assertEquals("said $status", e.detail)
                assertEquals(7000L, e.retryAfterMs)
            }
        }
    }

    @Test
    fun errorMessagesInEveryShape() {
        assertEquals("a", ModelApi.errorMessage("""{"error":{"message":"a"}}"""))
        assertEquals("b", ModelApi.errorMessage("""{"error":"b"}"""))
        assertEquals("c", ModelApi.errorMessage("""{"detail":"c"}"""))
        assertEquals("d", ModelApi.errorMessage("""{"message":"d"}"""))
        assertEquals("plain", ModelApi.errorMessage("plain"))
        assertNull(ModelApi.errorMessage(""))
    }

    @Test
    fun theKeyDoesNotFollowARedirect() {
        answer = { r ->
            if (r.path == "/v1/models") Triple(302, "", mapOf("Location" to "/elsewhere")) else Triple(200, "{}", emptyMap())
        }
        try {
            api().models()
            fail("a redirect isn't an answer")
        } catch (_: ModelException) {
        }
        assertEquals(listOf("/v1/models"), requests.map { it.path })
    }

    @Test
    fun aPageThatIsNotJsonIsSaidSo() {
        answer = { Triple(200, "<html>captive portal</html>", emptyMap()) }
        try {
            api().models()
            fail("not JSON")
        } catch (e: ModelException) {
            assertEquals(ModelException.Kind.BAD_ANSWER, e.kind)
        }
    }

    @Test
    fun models() {
        answer = { Triple(200, """{"object":"list","data":[{"id":"whisper-1"},{"id":"large-v3"}]}""", emptyMap()) }
        assertEquals(listOf("large-v3", "whisper-1"), api().models())
    }

    @Test
    fun voicesInEveryShape() {
        answer = { Triple(200, """{"voices":["af_bella","af_sky"]}""", emptyMap()) }
        assertEquals(listOf("af_bella", "af_sky"), api().voices())
        answer = { Triple(200, """{"data":[{"id":"x"},{"voice_id":"y"},{"name":"z"}]}""", emptyMap()) }
        assertEquals(listOf("x", "y", "z"), api().voices())
    }

    private fun wav(): File = File.createTempFile("piece", ".wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

    @Test
    fun transcriptionMultipartAndSegments() {
        answer = {
            Triple(200, """{"text":"Hola mundo.","segments":[{"start":0.5,"end":1.5,"text":" Hola "},{"start":1.5,"end":2.0,"text":"  "},{"start":2.0,"end":3.0,"text":"mundo."}]}""", emptyMap())
        }
        val t = api().transcribe(wav(), "es")
        val r = requests.single()
        assertEquals("/v1/audio/transcriptions", r.path)
        assertTrue(r.contentType!!.startsWith("multipart/form-data; boundary="))
        assertTrue(r.text.contains("name=\"model\"\r\n\r\nm\r\n"))
        assertTrue(r.text.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"))
        assertTrue(r.text.contains("name=\"language\"\r\n\r\nes\r\n"))
        assertTrue(r.text.contains("filename=\"audio.wav\""))
        assertTrue(r.text.endsWith("--\r\n"))
        assertEquals(listOf("Hola", "mundo."), t.segments.map { it.text })     // the blank one is gone
        assertEquals(0.5, t.segments[0].start, 0.0)
    }

    @Test
    fun autoLanguageIsLeftOut() {
        answer = { Triple(200, """{"text":"x"}""", emptyMap()) }
        api().transcribe(wav(), "auto")
        assertFalse(requests.single().text.contains("name=\"language\""))
    }

    /** gpt-4o-transcribe and the like only give json: it asks again for that. */
    @Test
    fun aModelWithoutTimesIsAskedForWhatItCanGive() {
        answer = { r ->
            if (r.text.contains("verbose_json")) {
                Triple(400, """{"error":{"message":"response_format 'verbose_json' is not compatible"}}""", emptyMap())
            } else {
                Triple(200, """{"text":"Solo texto."}""", emptyMap())
            }
        }
        val a = api()
        val t = a.transcribe(wav(), null)
        assertEquals(3, requests.size)                         // with words, without them, plain text
        assertEquals("Solo texto.", t.text)
        assertTrue(t.segments.isEmpty())
        a.transcribe(wav(), null)                              // the next piece isn't uploaded three times
        assertEquals(4, requests.size)
    }

    /** whisper-1, Groq: the times of each word only come if they're asked for. */
    @Test
    fun wordsAreAskedForAndRead() {
        answer = {
            Triple(200, """{"text":"Hola mundo.","segments":[{"start":0.0,"end":3.0,"text":"Hola mundo."}],"words":[{"word":" Hola","start":0.5,"end":1.0},{"word":"mundo.","start":2.0,"end":3.0},{"word":" ","start":3.0,"end":3.0}]}""", emptyMap())
        }
        val t = api().transcribe(wav(), "es")
        val r = requests.single().text
        assertTrue(r.contains("name=\"timestamp_granularities[]\"\r\n\r\nword\r\n"))
        assertTrue(r.contains("name=\"timestamp_granularities[]\"\r\n\r\nsegment\r\n"))
        assertEquals(listOf(Word(0.5, 1.0, "Hola"), Word(2.0, 3.0, "mundo.")), t.words)
    }

    @Test
    fun wordsInsideTheSegmentsAlsoCount() {
        answer = { Triple(200, """{"text":"x","segments":[{"start":0,"end":1,"text":"x","words":[{"text":"x","start":0.2,"end":0.8}]}]}""", emptyMap()) }
        assertEquals(listOf(Word(0.2, 0.8, "x")), api().transcribe(wav(), null).words)
    }

    /** A server that refuses only the extra field still gives its times per segment. */
    @Test
    fun aServerThatRefusesWordsIsAskedForSegments() {
        answer = { r ->
            if (r.text.contains("timestamp_granularities")) {
                Triple(400, """{"error":{"message":"unknown field"}}""", emptyMap())
            } else {
                Triple(200, """{"text":"x","segments":[{"start":1,"end":2,"text":"x"}]}""", emptyMap())
            }
        }
        val t = api().transcribe(wav(), null)
        assertEquals(2, requests.size)
        assertEquals(1, t.segments.size)
    }

    /** A bad piece says nothing of the service: the next one asks for everything again. */
    @Test
    fun aPieceThatFailsEverywhereDoesNotLowerWhatIsAsked() {
        var bad = true
        answer = {
            if (bad) Triple(400, """{"error":{"message":"audio too short"}}""", emptyMap())
            else Triple(200, """{"text":"x"}""", emptyMap())
        }
        val a = api()
        try {
            a.transcribe(wav(), null)
            fail("400")
        } catch (e: ModelException) {
            assertEquals(ModelException.Kind.BAD_REQUEST, e.kind)
        }
        assertEquals(3, requests.size)
        bad = false
        a.transcribe(wav(), null)
        assertTrue(requests.last().text.contains("timestamp_granularities"))
    }

    @Test
    fun aRefusedKeyIsNotAskedTwice() {
        answer = { Triple(401, """{"error":{"message":"bad key"}}""", emptyMap()) }
        try {
            api().transcribe(wav(), null)
            fail("401")
        } catch (e: ModelException) {
            assertEquals(ModelException.Kind.AUTH, e.kind)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun chat() {
        answer = { Triple(200, """{"choices":[{"message":{"role":"assistant","content":" Buenos días. "}}]}""", emptyMap()) }
        assertEquals("Buenos días.", api().chat("hola", 0.0, 64))
        val r = requests.single()
        assertEquals("/v1/chat/completions", r.path)
        assertEquals("user", r.json.getJSONArray("messages").getJSONObject(0).getString("role"))
        assertEquals("hola", r.json.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(64, r.json.getInt("max_tokens"))
    }

    /** Newer models want another name for the limit, and some refuse a temperature. */
    @Test
    fun chatAdaptsToWhatTheModelRefuses() {
        answer = { r ->
            when {
                r.json.has("max_tokens") -> Triple(400, """{"error":{"message":"Unsupported parameter: 'max_tokens'"}}""", emptyMap())
                r.json.has("temperature") -> Triple(400, """{"error":{"message":"'temperature' does not support 0.0"}}""", emptyMap())
                else -> Triple(200, """{"choices":[{"message":{"content":"ok"}}]}""", emptyMap())
            }
        }
        assertEquals("ok", api().chat("x", 0.0, 10))
        assertEquals(3, requests.size)
        assertEquals(10, requests.last().json.getInt("max_completion_tokens"))
    }

    @Test
    fun chatGivesUpOnOtherRefusals() {
        answer = { Triple(400, """{"error":{"message":"no such model"}}""", emptyMap()) }
        try {
            api().chat("x", 0.0, 10)
            fail("400")
        } catch (e: ModelException) {
            assertEquals("no such model", e.detail)
        }
        assertEquals(1, requests.size)
    }

    private fun tmp() = File.createTempFile("st-api", ".bin").also { it.deleteOnExit() }

    @Test
    fun theLanguageGoesAlongUntilTheServiceRefusesIt() {
        answer = { c -> if (c.json.has("language")) Triple(400, "{\"error\":\"language\"}", emptyMap()) else Triple(200, "audio", emptyMap()) }
        val a = api(format = "wav")
        a.speech("Да.", tmp(), "ru")
        assertEquals(listOf(true, true, false), requests.map { it.json.has("language") })
        assertEquals(listOf("wav", "mp3", "wav"), requests.map { it.json.getString("response_format") })
        requests.clear()
        a.speech("Нет.", tmp(), "ru")
        assertEquals(1, requests.size)
        assertFalse(requests[0].json.has("language"))
    }

    @Test
    fun aServiceThatTakesTheLanguageGetsIt() {
        answer = { Triple(200, "audio", emptyMap()) }
        val dest = tmp()
        api(format = "wav").speech("你好。", dest, "zh-cn")
        assertEquals("zh-cn", requests.single().json.getString("language"))
        assertEquals("audio", dest.readText())
    }

    @Test
    fun aServiceWithoutWavIsAskedForMp3FromThenOn() {
        answer = { c -> if (c.json.getString("response_format") == "wav") Triple(400, "{\"error\":\"wav\"}", emptyMap()) else Triple(200, "audio", emptyMap()) }
        val a = api(format = "wav")
        a.speech("Uno.", tmp())
        a.speech("Dos.", tmp())
        assertEquals(listOf("wav", "mp3", "mp3"), requests.map { it.json.getString("response_format") })
    }

    @Test
    fun aRefusalThatIsNotAboutTheFormatStops() {
        answer = { Triple(401, "{\"error\":\"key\"}", emptyMap()) }
        try {
            api(format = "wav").speech("Uno.", tmp(), "es")
            fail("401")
        } catch (e: ModelException) {
            assertEquals(ModelException.Kind.AUTH, e.kind)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun aVoiceGoesUpAsItIs() {
        answer = { Triple(201, "{\"id\":\"mi voz\"}", emptyMap()) }
        val audio = tmp().apply { writeBytes(byteArrayOf(1, 2, 3, 0, -1)) }
        api().uploadVoice("mi voz", audio)
        val r = requests.single()
        assertEquals("PUT", r.method)
        assertEquals("/v1/audio/voices/mi%20voz", r.path)
        assertEquals("Bearer sk-test", r.auth)
        assertArrayEquals(byteArrayOf(1, 2, 3, 0, -1), r.body)
    }

    @Test
    fun aServiceWithoutVoicesSaysSo() {
        answer = { Triple(404, "{\"error\":{\"message\":\"no\"}}", emptyMap()) }
        try {
            api().uploadVoice("x", tmp().apply { writeText("a") })
            fail("404")
        } catch (e: ModelException) {
            assertEquals(ModelException.Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun theBooksOfTheServerOnlyWithWhatABookMayHave() {
        answer = {
            Triple(
                200,
                """{"books": [{"id": "un libro", "title": "Un libro", "state": {"total": 5, "complete": false},
                    "manifest": [{"id": 1, "base": "cap01", "n": 2, "done": true, "file": "../../malo"}, {"id": 2, "base": "../malo", "done": true}],
                    "files": [{"name": "cap01.mp3", "size": 7}, {"name": "cap01.json", "size": 3}, {"name": "../malo.mp3", "size": 1},
                              {"name": "source.epub", "size": 2}, {"name": "playback.json", "size": 1}]},
                   {"id": "vacio", "title": "Nada", "manifest": [{"id": 1, "base": "../x"}], "files": []}]}""",
                emptyMap(),
            )
        }
        val b = api().books().single()
        assertEquals("/v1/storyteller/books", requests.single().path)
        assertEquals(Triple("un libro", "Un libro", 5), Triple(b.id, b.title, b.total))
        assertEquals(1, b.manifest.length())
        assertEquals("cap01", b.manifest.getJSONObject(0).getString("base"))
        assertFalse(b.manifest.getJSONObject(0).has("file"))
        assertEquals(listOf("cap01.mp3", "cap01.json", "source.epub"), b.files.map { it.name })
        assertEquals(12L, b.size)
    }

    @Test
    fun aFileOfABookArrivesAndSaysHowMuch() {
        answer = { Triple(200, "contenido", emptyMap()) }
        val dest = tmp()
        var last = 0L
        api().download("un libro", "cap01.mp3", dest) { last = it }
        assertEquals("/v1/storyteller/books/un%20libro/cap01.mp3", requests.single().path)
        assertEquals("contenido", dest.readText())
        assertEquals(9L, last)
    }

    private fun watched(every: Long) =
        ModelApi(Endpoint(url = "http://127.0.0.1:${server.localPort}/v1", key = "k", model = "m"), watchEveryMs = every)

    /** The server can no longer be reached while the call waits: it ends in moments, not after 10 minutes. */
    @Test
    fun aLongCallIsCutWhenTheServerCanNoLongerBeReached() {
        answer = {
            server.close()                           // no new connections; this one stays open, silent
            Thread.sleep(8000)
            Triple(200, "{}", emptyMap())
        }
        val start = System.currentTimeMillis()
        try {
            watched(100).chat("hola", 0.0, 64)
            fail("should have been cut")
        } catch (_: java.io.IOException) {
        }
        assertTrue(System.currentTimeMillis() - start < 5000)
    }

    /** A slow server that can still be reached is left alone. */
    @Test
    fun aSlowServerThatCanBeReachedIsLeftAlone() {
        answer = {
            Thread.sleep(600)
            Triple(200, """{"choices":[{"message":{"role":"assistant","content":"hola"}}]}""", emptyMap())
        }
        assertEquals("hola", watched(50).chat("hola", 0.0, 64))
    }
}
