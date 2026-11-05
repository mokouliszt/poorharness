package dev.mokouliszt.poordsh.modal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModalTokenTest {
    @Test
    fun readsTheLineModalShows() {
        val t = ModalAgent.Token.parse("modal token set --token-id ak-abc123XYZ --token-secret as-DEF456uvw --profile=me")!!
        assertEquals("ak-abc123XYZ", t.id)
        assertEquals("as-DEF456uvw", t.secret)
    }

    @Test
    fun readsBothPartsInAnyOrderOrLayout() {
        assertEquals(ModalAgent.Token("ak-1", "as-2"), ModalAgent.Token.parse("as-2\nak-1"))
        assertEquals(ModalAgent.Token("ak-1", "as-2"), ModalAgent.Token.parse("MODAL_TOKEN_ID=ak-1 MODAL_TOKEN_SECRET=as-2"))
    }

    @Test
    fun needsBoth() {
        assertNull(ModalAgent.Token.parse("ak-only"))
        assertNull(ModalAgent.Token.parse("as-only"))
        assertNull(ModalAgent.Token.parse(""))
    }

    @Test
    fun keptAsJson() {
        val t = ModalAgent.Token("ak-1", "as-2")
        assertEquals(t, ModalAgent.Token.fromJson(t.toJson().toString()))
        assertNull(ModalAgent.Token.fromJson(null))
        assertNull(ModalAgent.Token.fromJson("not json"))
    }
}
