package com.javimetallab.storyteller.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.javimetallab.storyteller.data.BookDetail
import com.javimetallab.storyteller.data.Chapter
import com.javimetallab.storyteller.data.Cue
import com.javimetallab.storyteller.data.Format
import com.javimetallab.storyteller.data.Library
import com.javimetallab.storyteller.data.Prefs
import com.javimetallab.storyteller.engine.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class PlayerUi(
    val bookId: String? = null,
    val title: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
    val chapters: List<Chapter> = emptyList(),
    val index: Int = 0,
    val cues: List<Cue> = emptyList(),
    val cuesLoaded: Boolean = false,
    val activeCue: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val speed: Float = 1f,
    val live: Boolean = false,
    val waiting: Boolean = false,
    val playbackError: Boolean = false,
)

/** What the player screen sees. The playing itself happens in PlaybackService. */
class PlayerModel(private val app: Application, private val scope: CoroutineScope, private val library: Library) {
    private val prefs = Prefs.get(app)
    private val _ui = MutableStateFlow(PlayerUi(speed = prefs.speed))
    val ui: StateFlow<PlayerUi> = _ui

    private var controller: MediaController? = null
    private val connectLock = Mutex()
    private var liveJob: Job? = null
    private var cuesJob: Job? = null
    private var cuesFor: String? = null
    private var wantPlay = false
    private var pendingCurrent: Chapter? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(state: Int) {
            _ui.update { it.copy(buffering = state == Player.STATE_BUFFERING) }
            if (state == Player.STATE_ENDED && _ui.value.live) {
                _ui.update { it.copy(waiting = true) }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) atEndOfItem()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncIndex()
        }

        override fun onPlayerError(error: PlaybackException) {
            _ui.update { it.copy(playbackError = true, playing = false) }
        }
    }

    /** Coming back to the app and opening a book can both ask at once: only one connection. */
    private suspend fun connect(): MediaController = connectLock.withLock {
        controller?.let { if (it.isConnected) return@withLock it }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        val c = suspendCancellableCoroutine { cont ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(app))
        }
        c.addListener(listener)
        controller = c
        c
    }

    /** Coming back to the app with something still playing: pick the book up again. */
    fun reattach() {
        scope.launch {
            val c = connect()
            val current = c.currentMediaItem ?: return@launch
            val (bookId, _) = PlaybackService.splitId(current.mediaId) ?: return@launch
            if (_ui.value.bookId == null) open(bookId, onlyAttach = true)
        }
    }

    fun open(bookId: String, title: String = "", onlyAttach: Boolean = false) {
        wantPlay = false
        _ui.update {
            it.copy(bookId = bookId, title = title, loading = true, failed = false, playbackError = false)
        }
        scope.launch {
            val c = connect()
            val detail = withContext(Dispatchers.IO) { library.detail(bookId) }
            if (detail == null) {
                _ui.update { it.copy(loading = false, failed = true) }
                return@launch
            }
            val already = c.currentMediaItem?.mediaId?.let { PlaybackService.splitId(it)?.first } == bookId
            val chapters = detail.chapters
            val live = Work.isActive(bookId)
            _ui.update {
                it.copy(
                    title = detail.summary.title, chapters = chapters, loading = false,
                    live = live, waiting = false, speed = prefs.speed,
                )
            }
            setPauseAtEnd(live)
            if (chapters.isEmpty()) {
                if (!already) {
                    c.stop()
                    c.clearMediaItems()
                }
            } else if (!already && !onlyAttach) {
                val (index, pos) = startPoint(bookId, chapters)
                c.setMediaItems(chapters.map { item(bookId, detail.summary.title, it) }, index, pos)
                c.setPlaybackParameters(PlaybackParameters(prefs.speed))
                c.prepare()
            }
            syncIndex()
            if (live) startLive(bookId) else stopLive()
        }
    }

    private fun startPoint(bookId: String, chapters: List<Chapter>): Pair<Int, Long> {
        val best = prefs.bookmark(bookId) ?: return 0 to 0L
        // By name: while it is narrated, a chapter's place in the list can change.
        val i = chapters.indexOfFirst { it.base == best.base }
        return when {
            i >= 0 -> i to best.pos
            best.base == null && best.cap in chapters.indices -> best.cap to best.pos
            else -> 0 to 0L
        }
    }

    private fun item(bookId: String, title: String, ch: Chapter): MediaItem {
        // A chapter that is growing is the same file with more in it: with the same address the
        // player would take it for what it already has and never read the rest.
        val file = Uri.fromFile(library.audioFile(bookId, ch))
        val uri = if (ch.partial) file.buildUpon().appendQueryParameter("frames", ch.frames.toString()).build() else file
        return MediaItem.Builder()
            .setMediaId(PlaybackService.mediaId(bookId, ch.base))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ch.title.ifBlank { title }).setArtist(title).build())
            .build()
    }

    private fun syncIndex() {
        val c = controller ?: return
        val i = c.currentMediaItemIndex
        _ui.update { it.copy(index = i, waiting = false) }
        loadCues(force = false)
    }

    private fun loadCues(force: Boolean) {
        val st = _ui.value
        val bookId = st.bookId ?: return
        val ch = st.chapters.getOrNull(st.index) ?: return
        val key = "$bookId/${ch.base}"
        if (!force && cuesFor == key) return
        cuesJob?.cancel()
        if (cuesFor != key) _ui.update { it.copy(cues = emptyList(), activeCue = -1, cuesLoaded = false) }
        cuesFor = key
        cuesJob = scope.launch {
            val cues = withContext(Dispatchers.IO) { library.cues(bookId, ch.base) }
            if (cuesFor == key) _ui.update { it.copy(cues = cues?.cues.orEmpty(), cuesLoaded = true) }
        }
    }

    /** Called several times a second while the screen shows the player. */
    fun tick() {
        val c = controller ?: return
        if (!c.isConnected) return
        val pos = c.currentPosition
        val dur = c.duration.takeIf { it > 0 } ?: 0L
        _ui.update { st ->
            st.copy(
                positionMs = pos, durationMs = dur, playing = c.isPlaying,
                activeCue = Format.cueAt(st.cues, pos / 1000.0),
            )
        }
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            wantPlay = false
            c.pause()
        } else {
            wantPlay = true
            _ui.update { it.copy(playbackError = false) }
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            // Finished: play starts it again. While it is being narrated, the end just means "wait for more".
            if (c.playbackState == Player.STATE_ENDED && !_ui.value.live) c.seekTo(0, 0)
            c.play()
        }
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
    }

    fun seekToCue(i: Int) {
        val cue = _ui.value.cues.getOrNull(i) ?: return
        seekTo((cue.start * 1000).toLong())
    }

    fun skip(deltaMs: Long) {
        val c = controller ?: return
        c.seekTo((c.currentPosition + deltaMs).coerceAtLeast(0))
    }

    fun goToChapter(i: Int) {
        val c = controller ?: return
        if (i !in 0 until c.mediaItemCount) return
        _ui.update { it.copy(waiting = false, playbackError = false) }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.seekTo(i, 0)
    }

    fun setSpeed(speed: Float) {
        prefs.speed = speed
        controller?.setPlaybackParameters(PlaybackParameters(speed))
        _ui.update { it.copy(speed = speed) }
    }

    /** A book deleted while it was loaded must not keep playing a file that is gone. */
    fun forget(bookId: String) {
        val c = controller
        if (_ui.value.bookId != bookId) return
        stopLive()
        c?.stop()
        c?.clearMediaItems()
        cuesFor = null
        _ui.value = PlayerUi(speed = prefs.speed)
    }

    // ---- listening while it is being narrated
    private fun setPauseAtEnd(on: Boolean) {
        controller?.sendCustomCommand(
            SessionCommand(PlaybackService.CMD_PAUSE_AT_END, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackService.ARG_ON, on) },
        )
    }

    private fun startLive(bookId: String) {
        liveJob?.cancel()
        liveJob = scope.launch {
            while (isActive) {
                delay(LIVE_POLL_MS)
                val fresh = withContext(Dispatchers.IO) { library.detail(bookId) } ?: continue
                merge(bookId, fresh)
                if (!Work.isActive(bookId)) {
                    _ui.update { it.copy(live = false) }
                    setPauseAtEnd(false)
                    return@launch
                }
            }
        }
    }

    private fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    /** Fits what there is now into the playlist, without disturbing what is playing. */
    private fun merge(bookId: String, fresh: BookDetail) {
        val c = controller ?: return
        val old = _ui.value.chapters
        if (_ui.value.bookId != bookId) return
        val title = fresh.summary.title
        val current = old.getOrNull(_ui.value.index)
        val merged = fresh.chapters
        for ((i, ch) in merged.withIndex()) {
            val had = old.firstOrNull { it.base == ch.base }
            if (had == null) {
                c.addMediaItem(i.coerceAtMost(c.mediaItemCount), item(bookId, title, ch))
            } else if (had.base != current?.base && (had.frames != ch.frames || had.done != ch.done)) {
                val at = (0 until c.mediaItemCount).firstOrNull {
                    c.getMediaItemAt(it).mediaId == PlaybackService.mediaId(bookId, ch.base)
                }
                if (at != null) c.replaceMediaItem(at, item(bookId, title, ch))
            }
        }
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()   // the first audio has arrived
        // Keep the old entry of the chapter playing until its item is swapped: atEndOfItem compares against it.
        val shown = merged.map { ch -> if (ch.base == current?.base) keepPlayingEntry(current, ch) else ch }
        _ui.update { it.copy(chapters = shown, index = c.currentMediaItemIndex) }
        pendingCurrent = merged.firstOrNull { it.base == current?.base }
        val grew = pendingCurrent?.let { it.n != current?.n } == true
        if (grew || cuesFor == null) loadCues(force = true)
        continueIfCaughtUp(bookId, title)
    }

    private fun keepPlayingEntry(playing: Chapter, fresh: Chapter) =
        fresh.copy(partial = playing.partial, frames = playing.frames, done = playing.done, file = playing.file)

    private fun atEndOfItem() {
        val st = _ui.value
        val bookId = st.bookId ?: return
        val ch = st.chapters.getOrNull(st.index) ?: return
        wantPlay = true
        if (!ch.partial) {
            val c = controller ?: return
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                c.play()
            } else {
                _ui.update { it.copy(waiting = true) }
            }
            return
        }
        _ui.update { it.copy(waiting = true) }
        continueIfCaughtUp(bookId, st.title)
    }

    /** Reached the end of what was narrated of this chapter: swap in the longer one, same place. */
    private fun continueIfCaughtUp(bookId: String, title: String) {
        val c = controller ?: return
        val st = _ui.value
        val playing = st.chapters.getOrNull(st.index) ?: return
        if (!playing.partial) {
            // It was waiting at the end of a finished chapter, and the next one has arrived.
            if (st.waiting && c.hasNextMediaItem()) {
                _ui.update { it.copy(waiting = false) }
                c.seekToNextMediaItem()
                c.prepare()
                if (wantPlay) c.play()
            }
            return
        }
        val fresh = pendingCurrent?.takeIf { it.base == playing.base } ?: return
        val more =fresh.done != playing.done || fresh.frames > playing.frames
        val nearEnd = st.waiting || (c.duration > 0 && c.duration - c.currentPosition < NEAR_END_MS)
        if (!more || !nearEnd) return
        val pos = c.currentPosition
        val at = c.currentMediaItemIndex
        val resumePlay = wantPlay || c.playWhenReady
        c.replaceMediaItem(at, item(bookId, title, fresh))
        c.seekTo(at, pos)
        c.prepare()
        if (resumePlay) c.play()
        _ui.update { s ->
            s.copy(
                waiting = false,
                chapters = s.chapters.map { if (it.base == fresh.base) fresh else it },
            )
        }
    }

    fun release() {
        stopLive()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private companion object {
        const val LIVE_POLL_MS = 4_000L
        const val NEAR_END_MS = 1_500L
    }
}
