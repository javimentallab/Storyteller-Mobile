package com.javimetallab.storyteller

import com.javimetallab.storyteller.engine.inOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.TreeSet

class InOrderTest {
    @Test
    fun oneAtATimeDoesNotGetAhead() = runBlocking {
        val asked = Collections.synchronizedList(ArrayList<Int>())
        val got = ArrayList<Int>()
        inOrder(3, 0, 1, work = { k -> asked += k; k * 10 }, deliver = { k, r ->
            assertEquals(k + 1, asked.size)
            got += r
        })
        assertEquals(listOf(0, 10, 20), got)
    }

    @Test
    fun aSlowOneDoesNotHoldBackTheOthers() = runBlocking(Dispatchers.Default) {
        val gates = List(100) { CompletableDeferred<Unit>() }
        val asked = Collections.synchronizedSet(TreeSet<Int>())
        val got = Collections.synchronizedList(ArrayList<Int>())
        val job = launch {
            inOrder(100, 0, 2, work = { k ->
                asked += k
                gates[k].await()
                k * 10
            }, deliver = { _, r -> got += r })
        }
        suspend fun waitAsked(want: IntRange) = withTimeout(5_000) {
            while (asked != want.toSet()) delay(5)
        }
        waitAsked(0..1)                          // two at a time, not one more
        for (k in 1..4) {                        // the first one stuck, the others go by
            gates[k].complete(Unit)
            waitAsked(0..k + 1)
        }
        gates[5].complete(Unit)
        delay(100)                               // the limit: 2 × 3 waiting for their turn
        assertEquals((0..5).toSet(), asked.toSet())
        assertTrue(got.isEmpty())                // nothing comes out without the first one
        gates.forEach { it.complete(Unit) }
        withTimeout(5_000) { job.join() }
        assertEquals(List(100) { it * 10 }, got.toList())
    }

    @Test
    fun anErrorComesAfterEverythingBeforeIt() {
        val got = ArrayList<Int>()
        assertThrows(IOException::class.java) {
            runBlocking {
                inOrder(10, 0, 3, work = { k ->
                    if (k == 2) throw IOException("refused")
                    if (k == 1) delay(50)
                    k
                }, deliver = { _, r -> got += r })
            }
        }
        assertEquals(listOf(0, 1), got)
    }

    @Test
    fun itStartsWhereItIsTold() = runBlocking {
        val got = ArrayList<Int>()
        inOrder(5, 3, 2, work = { it }, deliver = { k, r ->
            assertEquals(k, r)
            got += r
        })
        assertEquals(listOf(3, 4), got)
    }

    @Test
    fun nothingToDo() = runBlocking {
        inOrder<Int>(3, 3, 2, work = { error("nothing to ask for") }, deliver = { _, _ -> error("nothing to deliver") })
    }
}
