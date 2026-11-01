package com.javimetallab.storyteller

import com.javimetallab.storyteller.data.Parse
import com.javimetallab.storyteller.player.PlaybackService
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    @Test
    fun chapterNarratedHere() {
        val c = Parse.chapter(JSONObject("""{"id":1,"base":"cap01","title":"Uno","n":4,"duration":2.0,"done":true,"sr":24000,"frames":0,"file":"cap01.m4a"}"""), false)
        assertEquals("cap01.m4a", c.file)
        assertFalse(c.partial)
    }

    @Test
    fun chapterHalfwayIsPartial() {
        val c = Parse.chapter(JSONObject("""{"id":3,"base":"cap03","title":"Tres","n":1,"duration":0.5,"done":false,"sr":24000,"frames":12000,"file":"cap03.wav"}"""), false)
        assertTrue(c.partial)
        assertEquals(12000L, c.frames)
    }

    /** A book from the desktop app says nothing about the file: it is capNN.mp3. */
    @Test
    fun chapterFromTheDesktopApp() {
        val c = Parse.chapter(JSONObject("""{"id":1,"base":"cap01","title":"Uno","n":31,"duration":268.9,"done":true,"sr":24000,"frames":0}"""), false)
        assertEquals("cap01.mp3", c.file)
    }

    /** An imported audiobook has its whole audio from the start: it isn't partial while it is transcribed. */
    @Test
    fun importedIsNeverPartial() {
        val c = Parse.chapter(JSONObject("""{"id":1,"base":"cap01","title":"A","n":3,"duration":90,"done":false,"file":"cap01.m4b"}"""), true)
        assertFalse(c.partial)
        assertEquals("cap01.m4b", c.file)
    }

    @Test
    fun cues() {
        val c = Parse.cues(JSONObject("""{"title":"T","duration":2,"cues":[{"i":0,"start":0.5,"end":1.5,"text":"Hola."}]}"""))
        assertEquals(1, c.cues.size)
        assertEquals(0.5, c.cues[0].start, 0.0)
        assertEquals("Hola.", c.cues[0].text)
    }

    @Test
    fun oldDesktopBookmark() {
        val b = Parse.bookmark(JSONObject("""{"cap":2,"base":null,"pos":77,"updated":0}"""))
        assertNull(b.base)
        assertEquals(2, b.cap)
        assertEquals(77L, b.pos)
    }

    @Test
    fun mediaIds() {
        val id = PlaybackService.mediaId("my-book.v2", "cap01")
        assertEquals("my-book.v2" to "cap01", PlaybackService.splitId(id))
        assertNull(PlaybackService.splitId("nothing"))
        assertNull(PlaybackService.splitId("/cap01"))
        assertNull(PlaybackService.splitId("book/"))
    }
}
