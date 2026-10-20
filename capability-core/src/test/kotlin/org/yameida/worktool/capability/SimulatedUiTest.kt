package org.yameida.worktool.capability

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

/** Exercises the real admission/selection path; only the visible UI and its clock are simulated. */
class SimulatedUiTest {
    private class Ui : TextSurface {
        var currentChat = "selected-chat"
        var locked = false
        var draftText = ""
        var inputs = 0
        var clicks = 0
        var inputResult = true
        var clickResult = true
        var clickThrows = false
        var afterInput: () -> Unit = {}
        override fun check() = if (locked || currentChat != "selected-chat") "target_unavailable" else null
        override fun draft() = draftText
        override fun input(text: String): Boolean {
            inputs++; draftText = text; afterInput(); return inputResult
        }
        override fun clickSend(): Boolean {
            clicks++
            if (clickThrows) throw IllegalStateException("synthetic private UI details")
            return clickResult
        }
    }
    private class Fixture {
        val ui = Ui()
        var allowed = true
        var accessibility = true
        val tasks = ArrayDeque<Runnable>()
        val selection = SelectedConversation({ allowed && accessibility }, { 1L }, { "local-handle" })
        val target = Target("test-device", "test-epoch")
        val peer = VerifiedPeer("test-peer", target, CommandEngine.OPERATIONS.toSet())
        val engine = CommandEngine(target, {
            LocalPolicy(false, accessibility, allowed, false, selection.conversationId)
        }, selection, Executor { tasks.add(it) }, { 1L })
        val frames = mutableListOf<String>()
        val command = Command("test-command", target, "text.send",
            Wire.obj("conversationId" to selection.bindLocally(ui)!!, "text" to "synthetic message"), 1000)
        fun receive() = engine.receive(Wire.encode(command), peer, frames::add)
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun result() = Wire.decodeResult(frames.last())
        fun change(mode: String) {
            when (mode) {
                "conversation" -> ui.currentChat = "another-chat"
                "permission" -> allowed = false
                "accessibility" -> accessibility = false
                "lock" -> ui.locked = true
            }
        }
    }

    @Test fun queuedConversationChangeRevocationAndLockNeverTouchTheUi() {
        for (mode in listOf("conversation", "permission", "accessibility", "lock")) {
            val f = Fixture()
            f.receive(); f.change(mode); f.drain()
            assertEquals(mode, "rejected", f.result().state)
            val expected = when (mode) {
                "permission" -> "operation_disabled"
                "accessibility" -> "permission_denied"
                else -> "target_unavailable"
            }
            assertEquals(mode, expected, f.result().code)
            val terminal = f.result()
            f.ui.currentChat = "selected-chat"; f.ui.locked = false; f.allowed = true; f.accessibility = true
            f.receive(); f.drain()
            assertEquals(terminal, f.result())
            assertEquals(0, f.ui.inputs); assertEquals(0, f.ui.clicks)
        }
    }

    @Test fun conversationChangeRevocationAndLockAfterInputStayUnknownWithoutClickOrReplay() {
        for (mode in listOf("conversation", "permission", "accessibility", "lock")) {
            val f = Fixture()
            f.ui.afterInput = { f.change(mode) }
            f.receive(); f.drain()
            assertEquals(mode, "unknown", f.result().state)
            assertEquals("input_unconfirmed", f.result().code)
            val terminal = f.result()
            repeat(3) { f.receive(); f.drain(); assertEquals(terminal, f.result()) }
            assertEquals(1, f.ui.inputs); assertEquals(0, f.ui.clicks)
        }
    }

    @Test fun duplicateCommandBeforeAndAfterAmbiguousClickNeverSendsTwice() {
        for (outcome in listOf("true", "false", "exception")) {
            val f = Fixture()
            f.ui.clickResult = outcome == "true"; f.ui.clickThrows = outcome == "exception"
            f.receive(); f.receive(); f.drain()
            assertEquals("unknown", f.result().state)
            val terminal = f.result()
            repeat(3) { f.receive(); f.drain(); assertEquals(terminal, f.result()) }
            assertEquals(1, f.ui.inputs); assertEquals(1, f.ui.clicks)
            assertFalse(f.frames.joinToString().contains("private UI details"))
            assertNull(f.selection.conversationId)
        }
    }

    @Test fun unconfirmedInputRemainsUnknownAndDoesNotClick() {
        val f = Fixture(); f.ui.inputResult = false
        f.receive(); f.drain(); f.receive(); f.drain()
        assertEquals("unknown", f.result().state)
        assertEquals("input_unconfirmed", f.result().code)
        assertEquals(1, f.ui.inputs); assertEquals(0, f.ui.clicks)
    }

    @Test fun restartRejectsOldEpochInsteadOfReplayingUnknownSend() {
        val f = Fixture(); f.receive(); f.drain()
        val replacement = CommandEngine(f.target.copy(sessionId = "new-epoch"), { LocalPolicy() },
            { _, _ -> error("old command must not reach UI") }, Executor { it.run() }, { 1L })
        replacement.receive(Wire.encode(f.command), f.peer, f.frames::add)
        assertEquals("target_mismatch", f.result().code)
        assertEquals(1, f.ui.inputs); assertEquals(1, f.ui.clicks)
    }
}
