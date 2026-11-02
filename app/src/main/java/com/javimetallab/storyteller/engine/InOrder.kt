package com.javimetallab.storyteller.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val AHEAD = 3                 // pieces per request at a time that may wait for their turn

/**
 * work(k) for every k in [from, count), [parallel] at a time, and deliver(k, answer) in order. A
 * sliding window: when one ends the next is asked for, without waiting for the slowest, and what
 * gets ahead waits for its turn, up to a limit. They start in order, from a queue: the one needed
 * first never waits behind a later one. An error comes out on its turn, so everything before it is
 * delivered first. Same as in_order in cc_service.py, on the desktop.
 */
suspend fun <R> inOrder(
    count: Int,
    from: Int,
    parallel: Int,
    work: suspend (Int) -> R,
    deliver: suspend (Int, R) -> Unit,
) {
    val workers = parallel.coerceAtLeast(1)
    val limit = if (workers == 1) 1 else workers * AHEAD
    coroutineScope {
        val queue = Channel<Pair<Int, CompletableDeferred<Result<R>>>>(Channel.UNLIMITED)
        repeat(workers) {
            launch(Dispatchers.IO) {
                for ((at, answer) in queue) {
                    answer.complete(
                        try {
                            Result.success(work(at))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        },
                    )
                }
            }
        }
        val window = ArrayDeque<CompletableDeferred<Result<R>>>()
        var next = from
        for (k in from until count) {
            while (next < count && window.size < limit) {
                val answer = CompletableDeferred<Result<R>>()
                queue.trySend(next++ to answer)
                window.addLast(answer)
            }
            deliver(k, window.removeFirst().await().getOrThrow())
        }
        queue.close()
    }
}
