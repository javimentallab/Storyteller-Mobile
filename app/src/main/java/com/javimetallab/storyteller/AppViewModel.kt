package com.javimetallab.storyteller

import com.javimetallab.storyteller.engine.Task
import com.javimetallab.storyteller.engine.FetchBookTask
import com.javimetallab.storyteller.data.RemoteBook
import com.javimetallab.storyteller.data.PronStore
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.javimetallab.storyteller.data.BookSummary
import com.javimetallab.storyteller.data.Capability
import com.javimetallab.storyteller.data.Endpoint
import com.javimetallab.storyteller.data.Library
import com.javimetallab.storyteller.data.ModelApi
import com.javimetallab.storyteller.data.ModelException
import com.javimetallab.storyteller.data.Prefs
import com.javimetallab.storyteller.data.TranslationItem
import com.javimetallab.storyteller.engine.AudioCodec
import com.javimetallab.storyteller.engine.Docs
import com.javimetallab.storyteller.engine.Epub
import com.javimetallab.storyteller.engine.JobState
import com.javimetallab.storyteller.engine.NarrateTask
import com.javimetallab.storyteller.engine.Outcome
import com.javimetallab.storyteller.engine.Speller
import com.javimetallab.storyteller.engine.TextPrep
import com.javimetallab.storyteller.engine.TranscribeTask
import com.javimetallab.storyteller.engine.TranslateTask
import com.javimetallab.storyteller.engine.Translation
import com.javimetallab.storyteller.engine.Work
import com.javimetallab.storyteller.player.PlayerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

sealed interface Screen {
    data object Library : Screen
    data object Player : Screen
    data object Narrate : Screen
    data object Import : Screen
    data object Translate : Screen
    data object Models : Screen
    data object Pron : Screen
    data object Fetch : Screen

    /** A page inside the app: [title] is a string resource. */
    data class Web(val title: Int, val url: String) : Screen
}

data class PickedFile(val uri: Uri, val name: String, val size: Long)

/** What is about to be narrated: enough to know how much text goes to the service. */
data class EpubPreview(val file: File, val name: String, val title: String, val chapters: Int, val sentences: Int, val chars: Int)

/** How the last test of a model went. */
data class TestResult(val ok: Boolean, val text: String)

/**
 * The state of a book whose narration has not started yet: half done and resumable. Without it, a narration
 * that failed before its first save (a bad address, an EPUB that can't be read) left a folder with no index,
 * and the book vanished from the library.
 */
private const val PENDING_STATE = """{"total": 0, "sentences": 0, "complete": false}"""

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs.get(app)
    val library = Library(app)
    val player = PlayerModel(app, viewModelScope, library)

    private val stack = ArrayDeque<Screen>()
    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen

    private val _books = MutableStateFlow<List<BookSummary>>(emptyList())
    val books: StateFlow<List<BookSummary>> = _books
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded
    private val _translations = MutableStateFlow<List<TranslationItem>>(emptyList())
    val translations: StateFlow<List<TranslationItem>> = _translations

    val job: StateFlow<JobState?> = Work.current
    val queued: StateFlow<Int> = Work.queued
    val waiting: StateFlow<List<JobState>> = Work.waitingJobs

    private val _preview = MutableStateFlow<EpubPreview?>(null)
    val preview: StateFlow<EpubPreview?> = _preview
    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading

    private val _tests = MutableStateFlow<Map<Capability, TestResult?>>(emptyMap())
    val tests: StateFlow<Map<Capability, TestResult?>> = _tests
    private val _testing = MutableStateFlow<Capability?>(null)
    val testing: StateFlow<Capability?> = _testing

    /** One-off notices: an error code, perhaps with what the service said, or a text resource. */
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 8)
    val notices: SharedFlow<Notice> = _notices

    sealed interface Notice {
        data class Code(val code: String, val detail: String? = null) : Notice
        data class Text(@StringRes val res: Int) : Notice
    }

    private var pollJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { Work.changed.collect { refresh() } }
        viewModelScope.launch {
            Work.finished.collect { end ->
                if (end.outcome == Outcome.FAILED) _notices.tryEmit(Notice.Code(end.error ?: "internal", end.detail))
                refresh()
            }
        }
    }

    // ---- navigation
    fun go(to: Screen) {
        stack.addLast(_screen.value)
        _screen.value = to
    }

    fun canGoBack() = stack.isNotEmpty()

    fun back() {
        _screen.value = stack.removeLastOrNull() ?: return
    }

    fun openBook(id: String, title: String) {
        player.open(id, title)
        go(Screen.Player)
    }

    fun openPlayer() = go(Screen.Player)

    // ---- keeping up
    fun setForeground(on: Boolean) {
        pollJob?.cancel()
        pollJob = null
        if (!on) return
        player.reattach()
        pollJob = viewModelScope.launch {
            while (isActive) {
                refresh()
                // Only while something is growing: the rest changes through Work.changed.
                delay(if (job.value != null) 4_000 else 60_000)
            }
        }
    }

    suspend fun refresh() {
        val (books, translations) = withContext(Dispatchers.IO) { library.list() to library.translations() }
        _books.value = books
        _translations.value = translations
        _loaded.value = true
    }

    private fun refreshSoon() {
        viewModelScope.launch { refresh() }
    }

    fun stopJob(save: Boolean) = Work.stop(save)

    fun isConfigured(c: Capability) = prefs.endpoint(c).isSet

    // ---- models
    fun endpoint(c: Capability): Endpoint = prefs.endpoint(c)

    fun saveEndpoint(c: Capability, e: Endpoint) {
        prefs.saveEndpoint(c, e)
        _tests.value = _tests.value - c
    }

    /** The list a service offers, to pick from. Null if it doesn't offer one: the name is typed. */
    suspend fun listModels(e: Endpoint): List<String>? = listing { ModelApi(e).models() }

    suspend fun listVoices(e: Endpoint): List<String>? = listing { ModelApi(e).voices() }

    private suspend fun listing(block: () -> List<String>): List<String>? = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: IOException) {
            report(e)
            null
        }
    }

    private fun report(e: IOException) {
        if (e is ModelException) {
            _notices.tryEmit(Notice.Code("model_" + e.kind.name.lowercase(), e.detail))
        } else {
            _notices.tryEmit(Notice.Code("network", e.message))
        }
    }

    /** One real call to the service, with the smallest thing that proves it works. */
    fun testModel(c: Capability, e: Endpoint) {
        _testing.value = c
        _tests.value = _tests.value - c
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val api = ModelApi(e)
                    when (c) {
                        Capability.NARRATION -> {
                            val tmp = File(ctx.cacheDir, "test.audio")
                            api.speech("Hola.", tmp, e.language)
                            val pcm = AudioCodec.decodeAll(tmp)
                            tmp.delete()
                            TestResult(true, ctx.getString(R.string.test_narration_ok, "%.1f".format(pcm.seconds)))
                        }
                        Capability.TRANSCRIPTION -> {
                            val tmp = File(ctx.cacheDir, "test.wav")
                            AudioCodec.writeWav(tmp, ShortArray(16_000), 16_000, 16_000)
                            api.transcribe(tmp, null)
                            tmp.delete()
                            TestResult(true, ctx.getString(R.string.test_ok))
                        }
                        Capability.TRANSLATION -> {
                            val out = api.chat(Translation.prompt("Good morning.", "en", "es"), 0.0, 64)
                            TestResult(true, ctx.getString(R.string.test_translation_ok, out.take(80)))
                        }
                    }
                } catch (x: ModelException) {
                    TestResult(false, listOfNotNull(ctx.getString(R.string.http_status, x.status), x.detail).joinToString(": "))
                } catch (x: Exception) {
                    TestResult(false, x.message ?: x.javaClass.simpleName)
                }
            }
            _tests.value = _tests.value + (c to result)
            _testing.value = null
        }
    }

    /** Sends an audio file to the service as a new voice. Null if it went well; if not, why. */
    suspend fun uploadVoice(e: Endpoint, f: PickedFile, name: String): String? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val tmp = File(ctx.cacheDir, "voice-upload")
        try {
            copyToFile(f.uri, tmp)
            ModelApi(e).uploadVoice(name, tmp)
            null
        } catch (x: ModelException) {
            if (x.kind == ModelException.Kind.NOT_FOUND) {
                ctx.getString(R.string.voice_upload_unsupported)
            } else {
                listOfNotNull(ctx.getString(R.string.http_status, x.status), x.detail).joinToString(": ")
            }
        } catch (x: IOException) {
            x.message ?: x.javaClass.simpleName
        } finally {
            tmp.delete()
        }
    }

    /** The books of the user's own server, or why not. */
    suspend fun remoteBooks(): Pair<List<RemoteBook>?, String?> = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        try {
            ModelApi(prefs.endpoint(Capability.NARRATION)).books() to null
        } catch (x: ModelException) {
            null to if (x.kind == ModelException.Kind.NOT_FOUND) {
                ctx.getString(R.string.fetch_unsupported)
            } else {
                listOfNotNull(ctx.getString(R.string.http_status, x.status), x.detail).joinToString(": ")
            }
        } catch (x: IOException) {
            null to (x.message ?: x.javaClass.simpleName)
        }
    }

    fun remoteFolder(b: RemoteBook) = library.remoteFolder(b.id)

    fun fetchBook(b: RemoteBook) {
        val ctx = getApplication<Application>()
        Work.submit(ctx, FetchBookTask(ctx, b, library.dir(remoteFolder(b))))
    }

    fun pronTexts(lang: String) = PronStore.texts(PronStore.read(getApplication()), lang)

    fun savePron(lang: String, t: PronStore.Texts) {
        val ctx = getApplication<Application>()
        PronStore.save(ctx, PronStore.withTexts(PronStore.read(ctx), lang, t))
    }

    // ---- files
    fun describe(uri: Uri): PickedFile? {
        val resolver = getApplication<Application>().contentResolver
        return try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                val size = c.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0 && !c.isNull(it)) c.getLong(it) else -1L }
                if (name.isNullOrBlank()) null else PickedFile(uri, name, size)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToFile(uri: Uri, dest: File) {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw IOException("can't read")
        input.use { src -> dest.outputStream().use { src.copyTo(it) } }
    }

    // ---- narrating
    fun pickEpub(uri: Uri) {
        val picked = describe(uri)
        val ctx = getApplication<Application>()
        _reading.value = true
        _preview.value = null
        viewModelScope.launch {
            try {
                _preview.value = withContext(Dispatchers.IO) {
                    val tmp = File(ctx.cacheDir, "pending.epub")
                    copyToFile(uri, tmp)
                    val book = Epub.read(tmp)
                    val lang = prefs.endpoint(Capability.NARRATION).language
                    val speller = Speller(lang)           // cut as the narration will cut it (numbers written out)
                    val pieces = book.chapters.map { ch -> TextPrep.splitChunks(ch.text, lang, speller::spell).map { it.text } }
                    val fallback = picked?.name?.substringBeforeLast('.')?.replace('_', ' ').orEmpty()
                    EpubPreview(
                        tmp, picked?.name.orEmpty(), book.title.ifBlank { fallback }, book.chapters.size,
                        pieces.sumOf { it.size }, pieces.sumOf { p -> p.sumOf { it.length } },
                    )
                }
            } catch (_: Exception) {
                _notices.tryEmit(Notice.Code("cant_read_file"))
            } finally {
                _reading.value = false
            }
        }
    }

    fun clearPreview() {
        _preview.value = null
    }

    fun narrate(p: EpubPreview, prepareText: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    val d = library.newBook(p.title.ifBlank { "Book" })
                    if (!p.file.renameTo(File(d, "source.epub"))) p.file.copyTo(File(d, "source.epub"), overwrite = true)
                    Library.writeAtomic(File(d, "options.json"), JSONObject().put("prepare", prepareText).toString())
                    Library.writeAtomic(File(d, "gen_state.json"), PENDING_STATE)
                    d
                }
                Work.submit(ctx, NarrateTask(ctx, dir, p.title, resume = false))
                _preview.value = null
                back()
            } catch (_: IOException) {
                _notices.tryEmit(Notice.Code("cant_read_file"))
            }
        }
    }

    private fun resumeTask(b: BookSummary): Task {
        val ctx = getApplication<Application>()
        val dir = library.dir(b.id)
        return if (b.kind == "import") TranscribeTask(ctx, dir, b.title, null, "", null) else NarrateTask(ctx, dir, b.title, resume = true)
    }

    /** Behind whatever is running. A book already in line isn't added twice. */
    fun resumeBook(b: BookSummary) {
        if (!Work.isActive(b.id)) Work.submit(getApplication(), resumeTask(b))
    }

    /** Now: what is running saves what it has and waits right behind. */
    fun switchToBook(b: BookSummary) {
        if (!Work.isActive(b.id)) Work.submitNow(getApplication(), resumeTask(b))
    }

    fun switchToJob(id: Long) = Work.switchTo(id)

    fun regenerateBook(b: BookSummary) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val dir = library.dir(b.id)
            player.forget(b.id)
            // The old book goes aside (not deleted) and comes back if the regeneration is cancelled or
            // fails before narrating anything. Only what is needed to narrate it again is copied over.
            val backup = withContext(Dispatchers.IO) {
                val aside = library.setAside(b.id)
                dir.mkdirs()
                for (name in listOf("source.epub", "title.txt", "options.json")) {
                    File(aside, name).takeIf { it.exists() }?.copyTo(File(dir, name), overwrite = true)
                }
                Library.writeAtomic(File(dir, "gen_state.json"), PENDING_STATE)
                aside
            }
            prefs.forgetBookmark(b.id)
            Work.submit(ctx, NarrateTask(ctx, dir, b.title, resume = false, backup = backup))
        }
    }

    fun deleteBook(id: String) {
        player.forget(id)
        prefs.forgetBookmark(id)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.delete(id) }
            refresh()
        }
    }

    // ---- importing
    fun transcribe(file: PickedFile, language: String) {
        val ctx = getApplication<Application>()
        try {
            ctx.contentResolver.takePersistableUriPermission(file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not every provider gives one: the permission of the moment is enough for the copy.
        }
        viewModelScope.launch {
            val title = file.name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Audio" }
            val ext = file.name.substringAfterLast('.', "m4a").lowercase().filter { it.isLetterOrDigit() }.take(5)
            val dir = withContext(Dispatchers.IO) { library.newBook(title) }
            Work.submit(ctx, TranscribeTask(ctx, dir, title, file.uri, ext, language))
            back()
        }
    }

    // ---- translating
    fun translate(file: PickedFile, src: String, tgt: String) {
        val ctx = getApplication<Application>()
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext !in Docs.EXTENSIONS) {
            _notices.tryEmit(Notice.Code("bad_type"))
            return
        }
        viewModelScope.launch {
            try {
                val (dir, meta) = withContext(Dispatchers.IO) {
                    val d = File(library.translationsRoot, UUID.randomUUID().toString().replace("-", "").take(12))
                    d.mkdirs()
                    copyToFile(file.uri, File(d, "source.$ext"))
                    val stem = file.name.substringBeforeLast('.')
                    val outExt = if (ext == "pdf") "epub" else ext      // a PDF comes out as an EPUB
                    val m = JSONObject().put("name", file.name).put("src", src).put("tgt", tgt).put("state", "partial")
                        .put("source", "source.$ext").put("output", "output.$outExt").put("output_name", "$stem ($tgt).$outExt")
                    Library.writeAtomic(File(d, "meta.json"), m.toString())
                    d to m
                }
                Work.submit(ctx, TranslateTask(ctx, dir, meta))
            } catch (_: IOException) {
                _notices.tryEmit(Notice.Code("cant_read_file"))
            }
        }
    }

    fun resumeTranslation(t: TranslationItem) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val dir = File(library.translationsRoot, t.id)
            val meta = withContext(Dispatchers.IO) { JSONObject(File(dir, "meta.json").readText()) }
            Work.submit(ctx, TranslateTask(ctx, dir, meta))
        }
    }

    fun deleteTranslation(t: TranslationItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(library.translationsRoot, t.id).deleteRecursively() }
            refresh()
        }
    }

    fun saveTranslation(t: TranslationItem, dest: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val meta = JSONObject(File(File(library.translationsRoot, t.id), "meta.json").readText())
                    val out = File(File(library.translationsRoot, t.id), meta.getString("output"))
                    val sink = ctx.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("can't write")
                    sink.use { o -> out.inputStream().use { it.copyTo(o) } }
                }
                _notices.tryEmit(Notice.Text(R.string.saved_to_phone))
            } catch (_: Exception) {
                _notices.tryEmit(Notice.Code("cant_read_file"))
            }
        }
    }

    override fun onCleared() {
        player.release()
    }
}
