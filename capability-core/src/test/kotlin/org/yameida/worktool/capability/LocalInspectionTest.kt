package org.yameida.worktool.capability

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class LocalInspectionTest {
    private val target = Target("private-device", "private-epoch")
    private val peer = VerifiedPeer("private-peer", target, CommandEngine.OPERATIONS.toSet())
    private val enabled = LocalPolicy(false, true, true, true, "private-chat")
    private fun command(id: String = "private-command") = Command(id, target, "text.send",
        Wire.obj("conversationId" to "private-chat", "text" to "private-message"), 1000)

    @Test fun queriesAreReadOnlyDetachedAndNeverReplayUnknown() {
        val tasks = ArrayDeque<Runnable>(); var sends = 0; var policyAvailable = true
        val engine = CommandEngine(target, { if (policyAvailable) enabled else error("private-policy") }, { _, _ ->
            sends++; Outcome("unknown", "ui_click_unconfirmed", Wire.obj("private-result" to "private-evidence"))
        }, Executor { tasks.add(it) }, { 1L }, ledgerLimit = 1)
        val frames = mutableListOf<String>()
        assertTrue(engine.inspectLocal().records.isEmpty())
        assertNull(engine.localResult(1)); assertTrue(tasks.isEmpty())
        engine.receive(Wire.encode(command()), peer, frames::add)
        val accepted = engine.inspectLocal()
        assertEquals(1, accepted.pending)
        assertEquals("accepted", accepted.records.single().state)
        assertEquals(accepted.records.single(), engine.localResult(1))
        assertEquals(1, frames.size); assertEquals(1, tasks.size)
        tasks.removeFirst().run()
        val terminal = engine.localResult(1)!!
        assertEquals("unknown", terminal.state); assertEquals(3, terminal.sequence)
        repeat(4) { assertEquals(terminal, engine.localResult(1)); engine.inspectLocal() }
        assertEquals(1, sends); assertTrue(tasks.isEmpty()); assertEquals(3, frames.size)
        assertEquals("accepted", accepted.records.single().state)
        assertEquals(1, accepted.pending); assertEquals(0, engine.inspectLocal().pending)
        assertFalse(engine.inspectLocal().toString().contains("private-"))
        engine.receive(Wire.encode(command()), peer, frames::add)
        assertEquals("unknown", Wire.decodeResult(frames.last()).state)
        assertEquals(1, sends); assertEquals(1, engine.inspectLocal().records.size)
        engine.receive(Wire.encode(command("another-id")), peer, frames::add)
        assertEquals("ledger_full", Wire.decodeResult(frames.last()).code)
        assertEquals(1, engine.inspectLocal().records.size); assertNull(engine.localResult(2))
        policyAvailable = false; engine.close()
        val frameCount = frames.size
        repeat(4) {
            val closed = engine.inspectLocal()
            assertTrue(closed.closed); assertFalse(closed.policyAvailable)
            assertEquals(listOf(terminal), closed.records)
            assertEquals(terminal, engine.localResult(1))
            assertEquals(0, closed.pending)
            assertFalse(closed.toString().contains("private"))
        }
        assertEquals(1, sends); assertTrue(tasks.isEmpty()); assertEquals(frameCount, frames.size)
    }

    @Test fun queryingQueuedCommandsAfterCloseOrPolicyFailureDoesNotExecuteOrConsumeThem() {
        for (mode in listOf("closed", "policy_unavailable")) {
            val tasks = ArrayDeque<Runnable>(); var sends = 0; var policyAvailable = true
            val frames = mutableListOf<String>()
            val engine = CommandEngine(target, { if (policyAvailable) enabled else error("private-policy") },
                { _, _ -> sends++; Outcome("unknown", "ui_click_unconfirmed") },
                Executor { tasks.add(it) }, { 1L })
            engine.receive(Wire.encode(command()), peer, frames::add)
            val accepted = engine.localResult(1)!!
            if (mode == "closed") engine.close() else policyAvailable = false
            repeat(4) {
                val queued = engine.inspectLocal()
                assertEquals(1, queued.pending)
                assertEquals(listOf(accepted), queued.records)
                assertEquals(accepted, engine.localResult(1))
                for (missing in listOf(Int.MIN_VALUE, 0, 2, Int.MAX_VALUE))
                    assertNull(engine.localResult(missing))
            }
            assertEquals(1, tasks.size); assertEquals(1, frames.size); assertEquals(0, sends)
            tasks.removeFirst().run()
            val terminal = engine.localResult(1)!!
            assertEquals("rejected", terminal.state)
            assertEquals(if (mode == "closed") "operation_disabled" else mode, terminal.code)
            policyAvailable = true
            repeat(4) {
                assertEquals(terminal, engine.localResult(1))
                assertEquals(listOf(terminal), engine.inspectLocal().records)
                assertEquals(0, engine.inspectLocal().pending)
            }
            assertEquals(0, sends); assertTrue(tasks.isEmpty()); assertEquals(2, frames.size)
            assertEquals("accepted", accepted.state)
        }
    }

    @Test fun arbitraryOperationsAndExecutorCodesCannotLeakThroughLocalRecords() {
        val engine = CommandEngine(target, { enabled }, { _, _ ->
            Outcome("unknown", "private_customer_code", Wire.obj("body" to "private-evidence"))
        }, Executor { it.run() }, { 1L })
        engine.receive(Wire.encode(command()), peer) {}
        assertEquals("executor_result", engine.localResult(1)!!.code)
        val unknown = command("another-private-id").copy(operation = "private-operation")
        engine.receive(Wire.encode(unknown), peer.copy(operations = setOf(unknown.operation))) {}
        assertEquals("unsupported_operation", engine.localResult(2)!!.operation)
        assertEquals("unsupported_operation", engine.localResult(2)!!.code)
        assertFalse(engine.inspectLocal().toString().contains("private"))
    }

    @Test fun queriesSurfacePolicyFailureCloseAndDeliveryFailureWithoutGrantingOrSending() {
        var unavailable = false
        val engine = CommandEngine(target, { if (unavailable) error("private-policy") else enabled },
            { _, _ -> error("no send") }, Executor { it.run() }, { 1L })
        val initial = engine.inspectLocal()
        assertTrue(initial.policyAvailable); assertTrue(initial.selectionBound)
        unavailable = true
        val missing = engine.inspectLocal()
        assertFalse(missing.policyAvailable); assertTrue(missing.paused)
        assertFalse(missing.accessibility); assertFalse(missing.textAllowed)
        assertFalse(missing.eventsAllowed); assertFalse(missing.selectionBound)
        engine.receive(Wire.encode(command()), peer) { throw IllegalStateException("disconnected") }
        assertEquals("policy_unavailable", engine.localResult(1)!!.code)
        assertEquals(1L, engine.inspectLocal().deliveryFailures)
        unavailable = false; engine.close()
        val closed = engine.inspectLocal()
        assertTrue(closed.closed); assertFalse(closed.accessibility)
        assertFalse(closed.textAllowed); assertFalse(closed.eventsAllowed); assertFalse(closed.selectionBound)
        assertEquals(1, closed.records.size)
    }

    @Test fun unauthorizedTrafficAndOtherEpochsDoNotBecomeLocalCommandRecords() {
        val engine = CommandEngine(target, { LocalPolicy() }, { _, _ -> error("no send") }, Executor { it.run() })
        engine.receive(Wire.encode(command()), peer.copy(operations = emptySet())) {}
        engine.receive(Wire.encode(command().copy(target = target.copy(sessionId = "old"))), peer) {}
        assertTrue(engine.inspectLocal().records.isEmpty())
        assertNull(engine.localResult(1))
    }
}
