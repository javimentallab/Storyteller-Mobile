package com.javimetallab.storyteller

import com.javimetallab.storyteller.engine.JobQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobQueueTest {
    private fun queue(vararg ids: Long) = JobQueue<Long> { it }.also { q -> ids.forEach { q.addLast(it) } }

    @Test
    fun inOrderByDefault() {
        val q = queue(1, 2, 3)
        assertEquals(1L, q.removeFirstOrNull())
        assertEquals(listOf(2L, 3L), q.snapshot)
    }

    @Test
    fun theChosenOneGoesFirstAndTheRunningOneRightBehind() {
        val q = queue(2, 3, 4)
        assertTrue(q.toFront(4))
        assertEquals(listOf(4L, 2L, 3L), q.snapshot)
        q.putBehindFirst(1)                      // 1 was running
        assertEquals(listOf(4L, 1L, 2L, 3L), q.snapshot)
        assertEquals(4L, q.removeFirstOrNull())
        assertEquals(1L, q.removeFirstOrNull())
    }

    @Test
    fun switchingBackAndForth() {
        val q = queue(2)                         // 1 running, 2 waiting
        assertTrue(q.toFront(2))
        q.putBehindFirst(1)
        assertEquals(2L, q.removeFirstOrNull())  // 2 running, 1 waiting
        assertTrue(q.toFront(1))
        q.putBehindFirst(2)
        assertEquals(listOf(1L, 2L), q.snapshot)
    }

    @Test
    fun whatIsNotWaitingStaysAsItIs() {
        val q = queue(2, 3)
        assertFalse(q.toFront(9))
        assertEquals(listOf(2L, 3L), q.snapshot)
        assertTrue(q.any { it == 3L })
        assertFalse(q.any { it == 9L })
    }

    @Test
    fun anEmptyLine() {
        val q = queue()
        assertNull(q.removeFirstOrNull())
        q.putBehindFirst(1)
        assertEquals(listOf(1L), q.snapshot)
    }
}
