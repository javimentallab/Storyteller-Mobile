package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.ModelException
import com.javimetallab.storyteller.engine.Control
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** The QC asks the listener again when it is out of memory (503) instead of leaving the sentence unchecked. */
class RetryingTest {
    private val ctl = Control { }
    private val oom = ModelException(ModelException.Kind.SERVER, 503, "CUDA out of memory", retryAfterMs = 30_000)

    @Test
    fun outOfMemoryThenFineGetsTheAnswer() = runBlocking {
        var calls = 0
        // The 30 s of Retry-After are cut to 1 ms by the cap: if the cap failed, this would hang.
        val said = ctl.retrying(tries = 4, maxWaitMs = 1) {
            calls++
            if (calls < 3) throw oom
            "Hola, mundo."
        }
        assertEquals("Hola, mundo.", said)
        assertEquals(3, calls)
    }

    @Test
    fun alwaysOutOfMemoryGivesUpAfterItsTries() = runBlocking {
        var calls = 0
        try {
            ctl.retrying(tries = 4, maxWaitMs = 1) { calls++; throw oom }
            fail("should give up")
        } catch (e: ModelException) {
            assertSame(oom, e)
        }
        assertEquals(4, calls)
    }

    @Test
    fun noConnectionIsAlsoRetried() = runBlocking {
        var calls = 0
        val said = ctl.retrying(tries = 4, maxWaitMs = 1) {
            calls++
            if (calls == 1) throw IOException("connection refused")
            "ok"
        }
        assertEquals("ok" to 2, said to calls)
    }

    @Test
    fun aRefusalIsNotRetried() = runBlocking {
        var calls = 0
        try {
            ctl.retrying(tries = 4, maxWaitMs = 1) {
                calls++; throw ModelException(ModelException.Kind.AUTH, 401, "bad key")
            }
            fail("should not retry")
        } catch (_: ModelException) {
        }
        assertEquals(1, calls)
    }
}
