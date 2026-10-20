package org.yameida.worktool.capability

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContractTest {
    private val target = Target("device-a", "epoch-a")
    private val peer = VerifiedPeer("test-only-peer", target, CommandEngine.OPERATIONS.toSet())
    private fun command(id: String = "cmd-1", op: String = "text.send", text: String = "hello") =
        Command(id, target, op, if (op == "text.send") Wire.obj("conversationId" to "chat-local-1", "text" to text)
            else Wire.obj(), 2000000000000)
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private val enabled = LocalPolicy(false, true, true, true, "chat-local-1")
    private fun results(frames: List<String>) = frames.filter { Wire.parse(it)["kind"].asString == "result" }.map(Wire::decodeResult)

    @Test fun batchCommandsDoNotShareIdentityAndReplayNeverExecutes() {
        val queue = Queue(); val frames = mutableListOf<String>(); val sent = mutableListOf<String>()
        val engine = CommandEngine(target, { enabled }, { _, text ->
            sent.add(text); Outcome("unknown", "ui_click_unconfirmed")
        }, queue, { 1900000000000 })
        val a = command("a", text = "first"); val b = command("b", text = "second")
        val batch = """{"version":1,"kind":"batch","commands":[${Wire.encode(a)},${Wire.encode(b)}]}"""
        engine.receive(batch, peer, frames::add)
        assertEquals(listOf("accepted", "accepted"), results(frames).map { it.state })
        engine.receive(Wire.encode(a), peer, frames::add)
        queue.drain()
        assertEquals(listOf("first", "second"), sent)
        engine.receive(batch, peer, frames::add)
        assertEquals(2, sent.size)
        assertEquals(3, results(frames).last().sequence)
    }
    @Test fun conflictingPayloadOrPrincipalCannotReplaceRecordedCommand() {
        val q = Queue(); val frames = mutableListOf<String>(); var calls = 0
        val engine = CommandEngine(target, { enabled }, { _, _ -> calls++; Outcome("unknown", "clicked") }, q, { 1 })
        engine.receive(Wire.encode(command()), peer, frames::add)
        engine.receive(Wire.encode(command(text = "different")), peer, frames::add)
        assertEquals("id_conflict", Wire.parse(frames.last())["code"].asString)
        engine.receive(Wire.encode(command()), peer.copy(id = "another-peer"), frames::add)
        assertEquals("id_conflict", Wire.parse(frames.last())["code"].asString)
        q.drain(); assertEquals(1, calls)
    }
    @Test fun policyIsRecheckedAfterQueueingAndRejectionIsDeduplicated() {
        val q = Queue(); val frames = mutableListOf<String>(); var p = enabled
        val engine = CommandEngine(target, { p }, { _, _ -> error("must not run") }, q, { 1 })
        engine.receive(Wire.encode(command()), peer, frames::add)
        p = enabled.copy(paused = true); q.drain()
        assertEquals("paused", results(frames).last().code)
        p = enabled; engine.receive(Wire.encode(command()), peer, frames::add); q.drain()
        assertEquals("paused", results(frames).last().code)
    }
    @Test fun policyExceptionAfterAdmissionFinalizesAndReleasesSlot() {
        val q = Queue(); val frames = mutableListOf<String>(); var broken = false
        val engine = CommandEngine(target, { if (broken) throw IllegalStateException("unavailable") else enabled },
            { _, _ -> error("must not run") }, q, { 1 }, pendingLimit = 1)
        engine.receive(Wire.encode(command()), peer, frames::add)
        broken = true; q.drain()
        assertEquals("policy_unavailable", results(frames).last().code)
        engine.receive(Wire.encode(command()), peer, frames::add)
        assertEquals("rejected", results(frames).last().state)
        broken = false
        engine.receive(Wire.encode(command("query", "state.get")), peer, frames::add)
        q.drain()
        assertEquals("succeeded", results(frames).last().state)
    }
    @Test fun localPermissionUnknownOperationAuthAndTargetsFailClosed() {
        for ((p, op, expected) in listOf(
            Triple(enabled.copy(accessibility = false), "text.send", "permission_denied"),
            Triple(enabled.copy(textAllowed = false), "text.send", "operation_disabled"),
            Triple(enabled.copy(conversationId = null), "text.send", "target_unavailable"),
            Triple(enabled, "contacts.delete", "unsupported_operation")
        )) {
            val frames = mutableListOf<String>()
            val engine = CommandEngine(target, { p }, { _, _ -> error("must not run") }, Executor { it.run() }, { 1 })
            engine.receive(Wire.encode(command(op = op)), peer.copy(operations = setOf(op)), frames::add)
            assertEquals(expected, results(frames).single().code)
        }
        val frames = mutableListOf<String>()
        val engine = CommandEngine(target, { enabled }, { _, _ -> error("must not run") }, Executor { it.run() }, { 1 })
        engine.receive(Wire.encode(command()), peer.copy(operations = emptySet()), frames::add)
        assertEquals("unauthorized", results(frames).last().code)
        engine.receive(Wire.encode(command().copy(target = target.copy(sessionId = "old"))), peer, frames::add)
        assertEquals("target_mismatch", results(frames).last().code)
    }
    @Test fun busyLedgerFullExpiryAndCloseNeverExecute() {
        val q = Queue(); val frames = mutableListOf<String>(); var now = 1L
        val engine = CommandEngine(target, { enabled }, { _, _ -> error("must not run") }, q, { now }, 2, 1)
        engine.receive(Wire.encode(command("a")), peer, frames::add)
        engine.receive(Wire.encode(command("b")), peer, frames::add)
        assertEquals("busy", results(frames).last().code)
        engine.receive(Wire.encode(command("c")), peer, frames::add)
        assertEquals("ledger_full", results(frames).last().code)
        now = 2000000000001; q.drain()
        assertEquals("expired", results(frames).last().code)
        val other = CommandEngine(target, { enabled }, { _, _ -> error("must not run") }, q, { 1 })
        other.receive(Wire.encode(command()), peer, frames::add); other.close(); q.drain()
        assertEquals("operation_disabled", results(frames).last().code)
    }
    @Test fun ambiguityAndExecutorExceptionsAreExplicit() {
        val frames = mutableListOf<String>(); var ambiguous = true
        val engine = CommandEngine(target, { enabled }, { _, _ ->
            if (ambiguous) Outcome("rejected", "target_ambiguous") else throw IllegalStateException("secret")
        }, Executor { it.run() }, { 1 })
        engine.receive(Wire.encode(command("a")), peer, frames::add)
        assertEquals("target_ambiguous", results(frames).last().code)
        ambiguous = false; engine.receive(Wire.encode(command("b")), peer, frames::add)
        assertEquals("unknown", results(frames).last().state)
        assertFalse(frames.joinToString().contains("secret"))
    }
    @Test fun concurrentDuplicateAdmissionOnlyRunsOnceEvenWhenSinkFails() {
        val pool = Executors.newFixedThreadPool(4); val frames = ConcurrentLinkedQueue<String>()
        var calls = 0
        val queue = Queue()
        val engine = CommandEngine(target, { enabled }, { _, _ -> calls++; Outcome("unknown", "clicked") }, queue, { 1 })
        val done = CountDownLatch(24)
        repeat(24) { pool.execute { engine.receive(Wire.encode(command()), peer) { frames.add(it); throw Exception() }; done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS)); pool.shutdownNow()
        queue.drain(); assertEquals(1, calls); assertEquals(26, engine.deliveryFailures.get())
    }
    @Test fun malformedBatchIsAtomicForSyntaxAndExtensionsAreIgnored() {
        val frames = mutableListOf<String>(); var calls = 0
        val engine = CommandEngine(target, { enabled }, { _, _ -> calls++; Outcome("unknown", "clicked") }, Executor { it.run() }, { 1 })
        engine.receive("""{"version":1,"kind":"batch","commands":[${Wire.encode(command())},{}]}""", peer, frames::add)
        assertEquals(0, calls); assertEquals("error", Wire.parse(frames.single())["kind"].asString)
        val extension = Wire.parse(Wire.encode(command())).apply {
            addProperty("futureField", true); getAsJsonObject("params").addProperty("futureParam", 42)
        }.toString()
        engine.receive(extension, peer, frames::add)
        engine.receive(Wire.encode(command()), peer, frames::add)
        assertEquals(1, calls)
    }
    @Test fun strictWireRejectsVersionDuplicateKeysTypesDepthAndSizes() {
        val base = Wire.encode(command())
        val invalid = listOf(
            base.replace("\"version\":1", "\"version\":2"),
            base.replace("\"version\":1", "\"version\":\"1\""),
            base.replace("\"version\":1", "\"version\":1e0"),
            base.replace("\"version\":1", "\"version\":1,\"version\":1"),
            base.replace("\"expiresAt\":2000000000000", "\"expiresAt\":1.5"),
            base.replace("\"text\":\"hello\"", "\"text\":null"),
            base.replace("\"text\":\"hello\"", "\"text\":\"\\ud800\""),
            base.replace("\"text\":\"hello\"", "\"text\":\"hello\",\"\\ud800\":1"),
            base.replace("\"text\":\"hello\"", "\"text\":\"   \""),
            base + "{}",
            """{"version":1,"kind":"command","x":${"[".repeat(18)}0${"]".repeat(18)}}""",
            " ".repeat(Wire.MAX_FRAME + 1)
        )
        invalid.forEach { text -> try { Wire.commands(text); fail("accepted invalid frame") } catch (_: ProtocolException) {} }
        assertEquals(command(), Wire.commands(base).single())
        try {
            Wire.decodeResult(Wire.encode(Result("a", target, 3, "succeeded", "ok")).replace("\"sequence\":3", "\"sequence\":4294967299"))
            fail("overflowed result sequence")
        } catch (_: ProtocolException) {}
    }
    @Test fun invalidExecutorEvidenceBecomesReplayableUnknownResult() {
        val frames = mutableListOf<String>()
        val engine = CommandEngine(target, { enabled }, { _, _ ->
            Outcome("succeeded", "ok", Wire.obj("evidence" to "x".repeat(Wire.MAX_FRAME)))
        }, Executor { it.run() }, { 1 })
        engine.receive(Wire.encode(command()), peer, frames::add)
        val final = Wire.decodeResult(frames.last())
        assertEquals("unknown", final.state)
        assertEquals("invalid_executor_result", final.code)
        engine.receive(Wire.encode(command()), peer, frames::add)
        assertEquals(final, Wire.decodeResult(frames.last()))
    }
    @Test fun visibleMessagesBaselineAndAmbiguityDoNotInventEvents() {
        fun row(text: String) = ObservedMessage("chat-local-1", text, "remote")
        val v = VisibleMessages()
        assertTrue(v.update(listOf(row("a"))).isEmpty())
        assertEquals(listOf(row("b")), v.update(listOf(row("a"), row("b"))))
        assertTrue(v.update(listOf(row("x"), row("y"))).isEmpty())
        v.reset(); assertTrue(v.update(listOf(row("b"), row("b"))).isEmpty())
        assertTrue(v.update(listOf(row("b"), row("b"), row("b"))).isEmpty())
    }
    @Test fun fixturesAndActualGoEncoderInteroperate() {
        val fixture = File("protocol/v1/fixtures")
        assertEquals("你好，SDK 👋", Wire.commands(File(fixture, "command-text.json").readText()).single().params["text"].asString)
        assertEquals("state.get", Wire.commands(File(fixture, "command-state.json").readText()).single().operation)
        val dir = File(System.getProperty("contract.dir")).apply { mkdirs() }
        val go = File(dir, "go-command.json")
        assertTrue("Run Go contract export before this test", go.isFile)
        assertEquals("你好，SDK 👋", Wire.commands(go.readText()).single().params["text"].asString)
        assertEquals(2, Wire.commands(File(dir, "go-batch.json").readText()).size)
        File(dir, "kotlin-command.json").writeText(Wire.encode(command("kotlin-text", text = "你好，SDK 👋")))
        File(dir, "kotlin-result.json").writeText(Wire.encode(Result("go-text", target, 3, "unknown", "ui_click_unconfirmed")))
        File(dir, "kotlin-event.json").writeText(Wire.event("kotlin-event", target, 1900000000000,
            ObservedMessage("chat-local-1", "你好，SDK 👋", "remote")))
    }
    @Test fun sharedUnicodeWhitespaceCasesAgreeWithWireCommands() {
        val cases = com.google.gson.JsonParser.parseString(File("protocol/v1/text-cases.json").readText()).asJsonArray
        cases.forEach {
            val text = it.asJsonObject["text"].asString
            val valid = it.asJsonObject["valid"].asBoolean
            assertEquals(valid, Wire.validText(text))
            try {
                Wire.commands(Wire.encode(command(text = text)))
                assertTrue(valid)
            } catch (_: ProtocolException) { assertFalse(valid) }
        }
    }
}
