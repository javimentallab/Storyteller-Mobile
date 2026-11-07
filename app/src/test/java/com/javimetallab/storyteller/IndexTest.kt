package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Library
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Resuming or bringing a book must not lose what is already narrated. */
class IndexTest {
    private fun book(): File = Files.createTempDirectory("book").toFile().also { it.deleteOnExit() }

    private fun ids(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).getInt("id") }

    @Test
    fun noIndexIsANewBook() {
        assertNull(Library.readIndex(book()))
    }

    @Test
    fun anUnreadableIndexStopsInsteadOfStartingOver() {
        val b = book()
        File(b, "manifest.json").writeText("""[{"id":1,"done":tr""")    // cut off
        assertThrows(IOException::class.java) { Library.readIndex(b) }
    }

    @Test
    fun anIndexCutOffBeforeTheRenameIsInTheTmp() {
        val b = book()
        File(b, "manifest.json.tmp").writeText("""[{"id":1,"done":true},{"id":2,"done":true}]""")
        assertEquals(listOf(1, 2), ids(Library.readIndex(b)!!))
    }

    @Test
    fun bringingABookKeepsWhatOnlyThePhoneHas() {
        val local = JSONArray("""[{"id":1,"done":true,"file":"cap01.m4a"},{"id":6,"done":true,"file":"cap06.m4a"},{"id":7,"done":false,"n":3}]""")
        val remote = JSONArray("""[{"id":1,"done":true},{"id":2,"done":true}]""")
        val m = Library.mergeIndex(local, remote)
        assertEquals(listOf(1, 2, 6, 7), ids(m))
        assertTrue(!m.getJSONObject(0).has("file"))                     // chapter 1: what arrived (its files came with it)
        assertEquals("cap06.m4a", m.getJSONObject(2).getString("file"))
    }

    @Test
    fun bringingABookForTheFirstTime() {
        val remote = JSONArray("""[{"id":2,"done":true},{"id":1,"done":true}]""")
        assertEquals(listOf(1, 2), ids(Library.mergeIndex(null, remote)))
    }
}
