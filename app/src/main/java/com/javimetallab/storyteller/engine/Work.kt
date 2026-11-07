package com.javimetallab.storyteller.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.javimetallab.storyteller.MainActivity
import com.javimetallab.storyteller.R
import com.javimetallab.storyteller.data.ModelApi
import com.javimetallab.storyteller.data.ModelException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

enum class Stage { QUEUED, COPYING, READING, NARRATING, ENCODING, TRANSCRIBING, TRANSLATING, SAVING }

data class JobState(
    val id: Long,
    val kind: String,
    val title: String,
    val bookId: String? = null,
    val translationId: String? = null,
    val stage: Stage = Stage.QUEUED,
    val pct: Int = -1,
    val a: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val etaSec: Double? = null,
    val stopping: Boolean = false,
    val waitingMs: Long = 0,
)

enum class Outcome { DONE, STOPPED, CANCELED, FAILED }

data class Finished(val job: JobState, val outcome: Outcome, val error: String? = null, val detail: String? = null)

/** Thrown out of a task when the user stopped it. */
class Stopped : Exception()

/** The problem is the user's file, not the service nor the connection. */
class BadDocument(val code: String, val detail: String? = null) : Exception(code)

class Control(private val update: ((JobState) -> JobState) -> Unit) {
    enum class Mode { RUN, SAVE, CANCEL }

    @Volatile
    var mode = Mode.RUN
    private val apis = HashSet<ModelApi>()

    fun progress(f: (JobState) -> JobState) = update(f)

    fun watch(api: ModelApi): ModelApi {
        synchronized(apis) { apis += api }
        return api
    }

    fun stop(save: Boolean) {
        mode = if (save) Mode.SAVE else Mode.CANCEL
        synchronized(apis) { apis.toList() }.forEach { it.cancel() }
    }

    fun check() {
        if (mode != Mode.RUN) throw Stopped()
    }

    /** A busy service or a cut connection isn't the end: wait and ask again. A refusal is. */
    suspend fun <T> retrying(tries: Int = MAX_TRIES, maxWaitMs: Long = Long.MAX_VALUE, block: () -> T): T {
        var attempt = 0
        while (true) {
            check()
            try {
                return block()
            } catch (e: IOException) {
                check()
                val retry = (e as? ModelException)?.let { if (it.retriable) it.retryAfterMs ?: 0L else null }
                    ?: if (e is ModelException) throw e else 0L
                attempt++
                if (attempt >= tries) throw e
                val wait = minOf(maxWaitMs, maxOf(retry, 2_000L * attempt * attempt))
                update { it.copy(waitingMs = wait) }
                delay(wait)
                update { it.copy(waitingMs = 0) }
            }
        }
    }

    private companion object {
        const val MAX_TRIES = 7
    }
}

abstract class Task(val kind: String, val title: String, val bookId: String? = null, val translationId: String? = null) {
    abstract suspend fun run(ctl: Control)

    /** Stopped before it started, or cancelled: undo what was prepared. */
    open fun discard() {}

    /** The same job, to go on later from what it has saved. */
    open fun resumed(): Task = this
}

/**
 * The long jobs, one at a time and in order. They outlive the screen that started them, kept
 * alive by WorkService; what is done is on disk, so a job that dies is resumed, not lost.
 */
object Work {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val _current = MutableStateFlow<JobState?>(null)
    val current: StateFlow<JobState?> = _current
    private val _queued = MutableStateFlow(0)
    val queued: StateFlow<Int> = _queued
    private val _finished = MutableSharedFlow<Finished>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val finished: SharedFlow<Finished> = _finished

    /** Goes up each time something on disk changes shape: the library looks again. */
    val changed = MutableStateFlow(0)

    private val waiting = JobQueue<Pair<JobState, Task>> { it.first.id }
    private val _waitingJobs = MutableStateFlow<List<JobState>>(emptyList())
    val waitingJobs: StateFlow<List<JobState>> = _waitingJobs

    @Volatile private var switching = false

    private fun publishWaiting() {
        _queued.value = waiting.size
        _waitingJobs.value = waiting.snapshot.map { it.first }
    }
    private var control: Control? = null
    private var worker: Job? = null

    fun isActive(bookId: String) = _current.value?.bookId == bookId || synchronized(waiting) { waiting.any { it.first.bookId == bookId } }

    /** Stops what is running, saving what it has, and this one goes on. The other waits right behind it. */
    fun switchTo(id: Long) {
        synchronized(waiting) {
            if (!waiting.toFront(id)) return
            publishWaiting()
            if (control != null) switching = true
        }
        stop(save = true)
    }

    fun submitNow(context: Context, task: Task) = switchTo(submit(context, task))

    fun submit(context: Context, task: Task): Long {
        val job = JobState(ids.incrementAndGet(), task.kind, task.title, task.bookId, task.translationId)
        val app = context.applicationContext
        synchronized(waiting) {
            waiting.addLast(job to task)
            publishWaiting()
            if (worker?.isActive != true) worker = scope.launch { drain(app) }
        }
        changed.update { it + 1 }
        ContextCompat.startForegroundService(app, Intent(app, WorkService::class.java))
        return job.id
    }

    fun stop(save: Boolean) {
        val c = control ?: return
        _current.update { it?.copy(stopping = true) }
        c.stop(save)
    }

    private suspend fun drain(app: Context) {
        while (true) {
            val (job, task) = synchronized(waiting) {
                val next = waiting.removeFirstOrNull()
                publishWaiting()
                switching = false
                if (next == null) worker = null
                next
            } ?: return
            val ctl = Control { f -> _current.update { it?.let(f) } }
            control = ctl
            _current.value = job
            val end = try {
                task.run(ctl)
                Finished(_current.value ?: job, Outcome.DONE)
            } catch (_: Stopped) {
                if (ctl.mode == Control.Mode.CANCEL) {
                    runCatching { task.discard() }
                    Finished(_current.value ?: job, Outcome.CANCELED)
                } else if (switching) {
                    synchronized(waiting) {
                        waiting.putBehindFirst(job to task.resumed())
                        publishWaiting()
                    }
                    control = null
                    _current.value = null
                    changed.update { it + 1 }
                    continue                     // nothing to announce: it hasn't ended, it has given way
                } else {
                    Finished(_current.value ?: job, Outcome.STOPPED)
                }
            } catch (e: BadDocument) {
                Finished(_current.value ?: job, Outcome.FAILED, e.code, e.detail)
            } catch (e: ModelException) {
                Finished(_current.value ?: job, Outcome.FAILED, "model_" + e.kind.name.lowercase(), e.detail)
            } catch (e: IOException) {
                Finished(_current.value ?: job, Outcome.FAILED, "network", e.message)
            } catch (e: Exception) {
                Finished(_current.value ?: job, Outcome.FAILED, "internal", "${e.javaClass.simpleName}: ${e.message}")
            }
            control = null
            _current.value = null
            changed.update { it + 1 }
            _finished.tryEmit(end)
            WorkService.announce(app, end)
        }
    }
}

/** Keeps the process alive while a job runs, with its notification. */
class WorkService : Service() {
    private var scope: CoroutineScope? = null
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        channels(this)
        val first = notification(Work.current.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, first)
        }
        if (loop == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = s
            loop = s.launch {
                var idle = 0
                while (isActive) {
                    val job = Work.current.value
                    if (job == null && Work.queued.value == 0) {
                        if (++idle >= 3) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            break
                        }
                    } else {
                        idle = 0
                        manager.notify(ID, notification(job))
                    }
                    delay(1000)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps this kind of service at 6 h a day: it stops and saves, and it can be resumed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Work.stop(save = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(j: JobState?): Notification {
        val b = NotificationCompat.Builder(this, CH_WORK)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(j?.title ?: getString(R.string.work_channel))
            .setContentIntent(openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (j != null && j.pct >= 0) b.setProgress(100, j.pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onDestroy() {
        scope?.cancel()
        loop = null
        super.onDestroy()
    }

    companion object {
        private const val CH_WORK = "work"
        private const val CH_DONE = "done"
        private const val ID = 41

        private fun openApp(c: Context) = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun channels(c: Context) {
            val m = c.getSystemService(NotificationManager::class.java)
            m.createNotificationChannel(NotificationChannel(CH_WORK, c.getString(R.string.work_channel), NotificationManager.IMPORTANCE_LOW))
            m.createNotificationChannel(NotificationChannel(CH_DONE, c.getString(R.string.done_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }

        /** It may have taken hours and the app is probably closed: say how it ended. */
        fun announce(c: Context, end: Finished) {
            if (end.outcome != Outcome.DONE && end.outcome != Outcome.FAILED) return
            channels(c)
            val text = c.getString(if (end.outcome == Outcome.DONE) R.string.job_finished else R.string.job_failed)
            val n = NotificationCompat.Builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(end.job.title)
                .setContentText(text)
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .build()
            runCatching { c.getSystemService(NotificationManager::class.java).notify(1000 + end.job.id.toInt(), n) }
        }
    }
}
