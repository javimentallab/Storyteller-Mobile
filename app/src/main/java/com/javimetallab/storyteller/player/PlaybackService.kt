package com.javimetallab.storyteller.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.javimetallab.storyteller.MainActivity
import com.javimetallab.storyteller.data.Prefs

/** Plays in the background, with the notification and lock screen controls. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())

    private val saveTick = object : Runnable {
        override fun run() {
            saveBookmark()
            if (player.isPlaying) handler.postDelayed(this, SAVE_EVERY_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // MP3s from the desktop app are constant bitrate: seeking lands where the subtitles say.
        val extractors = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, extractors))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(saveTick)
                if (isPlaying) handler.postDelayed(saveTick, SAVE_EVERY_MS) else saveBookmark()
            }

            // A jump while paused (another chapter, a subtitle, «Go to position») isn't saved by the above:
            // if Android then kills the app without onDestroy, it would come back to before the jump.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !player.isPlaying) saveBookmark()
            }
        })
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()
    }

    private inner class Callback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_PAUSE_AT_END, Bundle.EMPTY))
                        .build()
                )
                .build()

        // The URI doesn't cross from the controller to the session on its own.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }.toMutableList()
        )

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_PAUSE_AT_END) {
                player.pauseAtEndOfMediaItems = args.getBoolean(ARG_ON)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    private fun saveBookmark() {
        val id = player.currentMediaItem?.mediaId ?: return
        val (bookId, base) = splitId(id) ?: return
        val prefs = Prefs.get(this)
        val pos = player.currentPosition
        // The saved place is in a chapter that isn't in the list now (it comes back when the book is
        // repaired or brought again): opening and closing without listening must not reset it to the start.
        val old = prefs.bookmark(bookId)
        if (pos <= 0L && old?.base != null && old.base != base &&
            (0 until player.mediaItemCount).none { splitId(player.getMediaItemAt(it).mediaId)?.second == old.base }
        ) return
        prefs.saveBookmark(bookId, player.currentMediaItemIndex, base, pos)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        saveBookmark()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    companion object {
        const val CMD_PAUSE_AT_END = "storyteller.pause_at_end"
        const val ARG_ON = "on"
        private const val SAVE_EVERY_MS = 5_000L

        fun mediaId(bookId: String, base: String) = "$bookId/$base"

        fun splitId(id: String): Pair<String, String>? {
            val i = id.lastIndexOf('/')
            return if (i <= 0 || i == id.length - 1) null else id.substring(0, i) to id.substring(i + 1)
        }
    }
}
