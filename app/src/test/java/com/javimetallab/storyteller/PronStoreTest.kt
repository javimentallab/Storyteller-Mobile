package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.PronStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PronStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aBrokenDictionaryIsSetAsideAndToldOnce() {
        val dir = tmp.newFolder()
        val f = File(dir, "pron.json")
        PronStore.takeUnreadable()
        assertTrue(PronStore.read(f).has("pron"))        // none yet: the initial one, and nothing to tell
        assertFalse(PronStore.takeUnreadable())
        f.writeText("{ cut off")
        assertTrue(PronStore.read(f).has("pron"))
        assertFalse(f.exists())
        val aside = dir.listFiles()!!.single()
        assertTrue(aside.name.startsWith("pron.unreadable-"))
        assertEquals("{ cut off", aside.readText())
        assertTrue(PronStore.takeUnreadable())
        assertFalse(PronStore.takeUnreadable())
    }

    @Test
    fun aGoodDictionaryIsReadAsItIs() {
        val f = File(tmp.newFolder(), "pron.json")
        f.writeText("""{"pron": {"a": "b"}}""")
        assertEquals("b", PronStore.read(f).getJSONObject("pron").getString("a"))
        assertFalse(PronStore.takeUnreadable())
    }
}
