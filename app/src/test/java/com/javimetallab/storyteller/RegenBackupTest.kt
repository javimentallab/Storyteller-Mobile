package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A regeneration that doesn't finish: the book set aside comes back unless the new one has something narrated. */
class RegenBackupTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun book(name: String, audio: String, index: String?): File {
        val d = tmp.newFolder(name)
        File(d, "cap01.m4a").writeText(audio)
        if (index != null) File(d, "manifest.json").writeText(index)
        return d
    }

    @Test
    fun whatCountsAsNarrated() {
        assertFalse(Library.narrated(book("none", "", null)))
        assertFalse(Library.narrated(book("empty", "", "[]")))
        assertFalse(Library.narrated(book("zero", "", """[{"id": 1, "n": 0}]""")))
        assertFalse(Library.narrated(book("broken", "", "[{")))
        assertTrue(Library.narrated(book("one", "", """[{"id": 1, "n": 1}]""")))
    }

    @Test
    fun withNothingNarratedTheOldBookComesBack() {
        val old = book("old", "old audio", """[{"id": 1, "n": 9, "done": true}]""")
        val new = book("new", "", "[]")
        Library.settleBackup(old, new)
        assertEquals("old audio", File(new, "cap01.m4a").readText())
        assertTrue(Library.narrated(new))
        assertFalse(old.exists())
    }

    @Test
    fun withSomethingNarratedTheNewOneStaysAndTheCopyGoes() {
        val old = book("old", "old audio", """[{"id": 1, "n": 9, "done": true}]""")
        val new = book("new", "new audio", """[{"id": 1, "n": 2, "done": false}]""")
        Library.settleBackup(old, new)
        assertEquals("new audio", File(new, "cap01.m4a").readText())
        assertFalse(old.exists())
    }
}
