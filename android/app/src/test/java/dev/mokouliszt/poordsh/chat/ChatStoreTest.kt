package dev.mokouliszt.poordsh.chat

import dev.mokouliszt.poordsh.backend.Place
import dev.mokouliszt.poordsh.bridge.jsonOf
import dev.mokouliszt.poordsh.bridge.objects
import dev.mokouliszt.poordsh.bridge.strings
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun msg(id: String, role: String = "assistant") = jsonOf("id" to id, "role" to role, "parts" to JSONArray())
    private fun ids(a: JSONArray) = a.objects().map { it.getString("id") }

    @Test fun segmentsKeepTheirOwnMessagesInOrder() {
        val store = ChatStore(tmp.root)
        val id = store.create()
        assertTrue(store.meta(id)!!.getJSONArray("engines").length() == 0)

        store.addSegment(id, ChatStore.Segment("opencode", "opencode:s1", "Free model"))
        store.replaceSegmentMessages(id, "opencode:s1", JSONArray().put(msg("a", "user")).put(msg("b")))
        store.addSegment(id, ChatStore.Segment("codex", "codex:t1", "GPT"))
        store.replaceSegmentMessages(id, "codex:t1", JSONArray().put(msg("c", "user")))
        // A later copy of the first segment replaces only that segment.
        store.replaceSegmentMessages(id, "opencode:s1", JSONArray().put(msg("a", "user")).put(msg("b")).put(msg("b2")))
        store.replaceSegmentMessages(id, "codex:t1", JSONArray().put(msg("c", "user")).put(msg("d")))

        val all = store.messages(id)
        assertEquals(listOf("a", "b", "b2", "c", "d"), ids(all))
        val d = all.objects().last()
        assertEquals("codex:t1", d.getString("segment"))
        assertEquals("codex", d.getString("engine"))
        assertEquals("GPT", d.getString("segmentModel"))

        assertEquals(id, store.chatOfSession("codex:t1"))
        assertTrue(store.isFirstSegment("opencode:s1"))
        assertFalse(store.isFirstSegment("codex:t1"))
        assertEquals("codex", store.currentSegment(id)!!.engine)
        assertEquals("GPT", store.segmentOf("codex:t1")!!.model)
        assertEquals(listOf("opencode", "codex"), store.meta(id)!!.getJSONArray("engines").strings())
        assertEquals("codex", store.meta(id)!!.getString("engine"))
    }

    @Test fun unknownSegmentsAreIgnored() {
        val store = ChatStore(tmp.root)
        val id = store.create()
        store.replaceSegmentMessages(id, "opencode:nope", JSONArray().put(msg("x")))
        assertEquals(0, store.messages(id).length())
    }

    @Test fun survivesARestart() {
        val dir = tmp.newFolder("chats")
        val first = ChatStore(dir)
        val id = first.create("Title")
        first.addSegment(id, ChatStore.Segment("codex", "codex:t1", null))
        first.replaceSegmentMessages(id, "codex:t1", JSONArray().put(msg("m1")))
        first.setModel(id, "codex:gpt", "high")

        val again = ChatStore(dir)
        assertEquals(listOf(id), again.list().map { it.getString("id") })
        assertEquals("Title", again.title(id))
        assertEquals(id, again.chatOfSession("codex:t1"))
        assertEquals(listOf("m1"), ids(again.messages(id)))
        val meta = again.meta(id)!!
        assertEquals("codex:gpt", meta.getString("model"))
        assertEquals("high", meta.getString("effort"))
        assertTrue(File(dir, "$id.json").isFile)
    }

    @Test fun clearingTheEffortStoresNoEffort() {
        val store = ChatStore(tmp.root)
        val id = store.create()
        store.setModel(id, "codex:gpt", "high")
        store.setModel(id, "opencode:opencode/free", null)
        assertTrue(store.meta(id)!!.isNull("effort"))
    }

    @Test fun deleteReturnsSegmentsAndForgetsSessions() {
        val store = ChatStore(tmp.root)
        val id = store.create()
        store.addSegment(id, ChatStore.Segment("opencode", "opencode:s1", null))
        val segs = store.delete(id)
        assertEquals(listOf("opencode:s1"), segs.map { it.sessionId })
        assertFalse(store.exists(id))
        assertNull(store.chatOfSession("opencode:s1"))
        assertFalse(File(tmp.root, "$id.json").exists())
        assertTrue(store.delete(id).isEmpty())
    }

    @Test fun pendingDeletesAndImportMarkers() {
        val store = ChatStore(tmp.root)
        store.addPendingDelete("codex", "codex:t1")
        store.addPendingDelete("codex", "codex:t2")
        store.addPendingDelete("opencode", "opencode:s1")
        assertEquals(listOf("codex:t1", "codex:t2"), store.takePendingDeletes("codex"))
        assertTrue(store.takePendingDeletes("codex").isEmpty())
        assertEquals(listOf("opencode:s1"), ChatStore(tmp.root).takePendingDeletes("opencode"))

        assertTrue(store.markImported("codex"))
        assertFalse(store.markImported("codex"))
        assertTrue(store.markImported("opencode"))
    }

    @Test fun placeIsFixedOnceAndSurvivesReload() {
        val store = ChatStore(tmp.root)
        val id = store.create()
        // Chats from before 0.3 (no place recorded) ran in Tensorlake.
        assertEquals(Place.CLOUD, store.place(id))
        assertNull(store.meta(id)!!.opt("place").takeIf { it != org.json.JSONObject.NULL })
        store.setPlace(id, Place.PHONE)
        // A later key must not move a chat whose files are on the phone.
        store.setPlace(id, Place.CLOUD)
        assertEquals(Place.PHONE, store.place(id))
        assertEquals("phone", store.meta(id)!!.getString("place"))
        assertEquals(Place.PHONE, ChatStore(tmp.root).place(id))
    }
}
