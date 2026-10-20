package org.yameida.worktool.capability

import org.junit.Assert.*
import org.junit.Test

class SelectionTest {
    private class Surface : TextSurface {
        var problem: String? = null
        var content = ""
        var inputs = 0
        var clicks = 0
        var afterInput: () -> Unit = {}
        override fun check() = problem
        override fun draft() = content
        override fun input(text: String): Boolean { inputs++; content = text; afterInput(); return true }
        override fun clickSend(): Boolean { clicks++; return true }
    }
    @Test fun localHandleIsSingleUseAndClickDoesNotClaimDelivery() {
        val s = Surface(); val selected = SelectedConversation({ true })
        val id = selected.bindLocally(s)!!
        val result = selected.sendText(id, "hello")
        assertEquals("unknown", result.state); assertEquals(1, s.clicks)
        assertEquals("target_unavailable", selected.sendText(id, "again").code)
        assertEquals(1, s.clicks)
    }
    @Test fun ambiguousChangedExpiredPausedAndDirtyDraftNeverClick() {
        for (mode in listOf("ambiguous", "changed", "expired", "paused", "draft")) {
            var enabled = true; var now = 1L; val s = Surface()
            val selected = SelectedConversation({ enabled }, { now })
            val id = selected.bindLocally(s)!!
            when (mode) {
                "ambiguous" -> s.problem = "target_ambiguous"
                "changed" -> selected.invalidate()
                "expired" -> now += 60_001
                "paused" -> enabled = false
                "draft" -> s.content = "user's draft"
            }
            assertEquals("rejected", selected.sendText(id, "hello").state)
            assertEquals(0, s.inputs); assertEquals(0, s.clicks)
        }
    }
    @Test fun selectionAndPermissionAreRecheckedBetweenInputAndClick() {
        var enabled = true
        val s = Surface(); val selected = SelectedConversation({ enabled })
        val id = selected.bindLocally(s)!!
        s.afterInput = { enabled = false }
        assertEquals("unknown", selected.sendText(id, "hello").state)
        assertEquals(1, s.inputs); assertEquals(0, s.clicks)
    }
    @Test fun invalidBindingAndWrongHandleNeverChooseFirstResult() {
        val s = Surface(); val selected = SelectedConversation({ true })
        s.problem = "target_ambiguous"; assertNull(selected.bindLocally(s))
        s.problem = null; val id = selected.bindLocally(s)!!
        assertEquals("target_unavailable", selected.sendText("display-name", "hello").code)
        assertEquals(id, selected.conversationId); assertEquals(0, s.inputs)
    }
    @Test fun localInvalidationDuringInputPreventsClick() {
        val s = Surface(); val selected = SelectedConversation({ true })
        val id = selected.bindLocally(s)!!
        s.afterInput = { selected.invalidate() }
        assertEquals("unknown", selected.sendText(id, "hello").state)
        assertEquals(0, s.clicks)
    }
}
