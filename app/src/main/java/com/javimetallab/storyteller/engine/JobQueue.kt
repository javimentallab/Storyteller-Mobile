package com.javimetallab.storyteller.engine

/**
 * The jobs waiting their turn. One runs at a time, but the user can change which: the chosen one
 * goes first and the one that was running waits right behind it.
 */
class JobQueue<T>(private val idOf: (T) -> Long) {
    private val items = ArrayDeque<T>()

    val size get() = items.size
    val snapshot get() = items.toList()

    fun addLast(item: T) = items.addLast(item)

    fun removeFirstOrNull() = items.removeFirstOrNull()

    fun any(test: (T) -> Boolean) = items.any(test)

    /** False if it isn't waiting: it has just started, or it never was. */
    fun toFront(id: Long): Boolean {
        val i = items.indexOfFirst { idOf(it) == id }
        if (i < 0) return false
        items.addFirst(items.removeAt(i))
        return true
    }

    fun putBehindFirst(item: T) = items.add(minOf(1, items.size), item)
}
