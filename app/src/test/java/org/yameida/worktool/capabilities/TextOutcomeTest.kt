package org.yameida.worktool.capabilities

import org.junit.Assert.assertEquals
import org.junit.Test
import org.yameida.worktool.capability.SelectedConversation
import org.yameida.worktool.capability.TextSurface

class TextOutcomeTest {
    @Test fun neitherPlatformClickBooleanProvesDeliveryOrNonDelivery() {
        for (clickResult in listOf(true, false)) {
            var draft = ""
            var clicks = 0
            val surface = object : TextSurface {
                override fun check(): String? = null
                override fun draft() = draft
                override fun input(text: String): Boolean { draft = text; return true }
                override fun clickSend(): Boolean { clicks++; return clickResult }
            }
            val selected = SelectedConversation({ true })
            val id = selected.bindLocally(surface)!!
            val result = selected.sendText(id, "synthetic text")
            assertEquals("unknown", result.state)
            assertEquals("ui_click_unconfirmed", result.code)
            assertEquals(1, clicks)
            assertEquals("rejected", selected.sendText(id, "do not retry").state)
            assertEquals(1, clicks)
        }
    }
    @Test fun falseInputReturnCanStillMeanDraftChangedButNeverTriggersClick() {
        var draft = ""
        var clicks = 0
        val surface = object : TextSurface {
            override fun check(): String? = null
            override fun draft() = draft
            override fun input(text: String): Boolean { draft = text; return false }
            override fun clickSend(): Boolean { clicks++; return true }
        }
        val selected = SelectedConversation({ true })
        val result = selected.sendText(selected.bindLocally(surface)!!, "synthetic text")
        assertEquals("unknown", result.state)
        assertEquals("input_unconfirmed", result.code)
        assertEquals(0, clicks)
    }
}
