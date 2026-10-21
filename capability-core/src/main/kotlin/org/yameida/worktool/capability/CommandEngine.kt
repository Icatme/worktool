package org.yameida.worktool.capability

import com.google.gson.JsonObject
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** Constructed only by a trusted adapter after authentication, never deserialized from a frame. */
data class VerifiedPeer(val id: String, val target: Target, val operations: Set<String>)
data class LocalPolicy(val paused: Boolean = true, val accessibility: Boolean = false,
                       val textAllowed: Boolean = false, val eventsAllowed: Boolean = false,
                       val conversationId: String? = null)
data class Outcome(val state: String, val code: String, val data: JsonObject = JsonObject()) {
    init {
        require(state in setOf("succeeded", "failed", "partial_failure", "unknown", "rejected"))
        require(code.matches(Regex("[a-z0-9_]{1,64}")))
    }
}
fun interface CapabilityExecutor { fun sendText(conversationId: String, text: String): Outcome }
fun interface FrameSink { fun send(frame: String) }

/** Single serialized executor for UI work; admission and deduplication are thread safe. */
class CommandEngine(
    val target: Target,
    private val policy: () -> LocalPolicy,
    private val executor: CapabilityExecutor,
    private val scheduler: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ledgerLimit: Int = 1024,
    private val pendingLimit: Int = 32
) {
    init { require(Wire.validId(target.deviceId) && Wire.validId(target.sessionId)); require(ledgerLimit > 0 && pendingLimit > 0) }
    private data class Entry(val recordId: Int, val command: Command, val owner: String, var result: Result)
    private val lock = Any()
    private val ledger = linkedMapOf<String, Entry>()
    private var pending = 0
    private var closed = false
    val deliveryFailures = AtomicLong()

    fun receive(frame: String, peer: VerifiedPeer, sink: FrameSink) {
        val commands = try { Wire.commands(frame) } catch (e: ProtocolException) {
            emit(sink, Wire.error(e.code)); return
        }
        commands.forEach { admit(it, peer, sink) }
    }
    private fun admit(c: Command, peer: VerifiedPeer, sink: FrameSink) {
        var execute = false
        val reply = synchronized(lock) {
            val authorization = when {
                c.target != target -> "target_mismatch"
                peer.id.isBlank() || peer.target != target || c.operation !in peer.operations -> "unauthorized"
                else -> null
            }
            if (authorization != null) return@synchronized Wire.encode(result(c, 1, "rejected", authorization))
            val prior = ledger[c.id]
            if (prior != null) return@synchronized if (prior.owner == peer.id && prior.command == c)
                Wire.encode(prior.result) else Wire.error("id_conflict", c.id)
            if (ledger.size >= ledgerLimit) return@synchronized Wire.encode(result(c, 1, "rejected", "ledger_full"))
            val reject = if (closed) "operation_disabled" else gate(c) ?: if (pending >= pendingLimit) "busy" else null
            val r = if (reject == null) result(c, 1, "accepted", "accepted")
                else result(c, 1, "rejected", reject)
            ledger[c.id] = Entry(ledger.size + 1, c, peer.id, r)
            if (reject == null) { pending++; execute = true }
            Wire.encode(r)
        }
        emit(sink, reply)
        if (execute) {
            try { scheduler.execute { run(c, sink) } }
            catch (_: Exception) { finish(c, Outcome("rejected", "busy"), sink) }
        }
    }
    private fun gate(c: Command): String? = try { checkPolicy(c) } catch (_: Exception) { "policy_unavailable" }
    private fun checkPolicy(c: Command): String? {
        if (c.expiresAt <= clock()) return "expired"
        if (c.operation !in OPERATIONS) return "unsupported_operation"
        if (c.operation == "text.send") {
            val p = policy()
            if (p.paused) return "paused"
            if (!p.accessibility) return "permission_denied"
            if (!p.textAllowed) return "operation_disabled"
            if (p.conversationId != c.params["conversationId"].asString) return "target_unavailable"
        }
        return null
    }
    private fun run(c: Command, sink: FrameSink) {
        val rejection = synchronized(lock) {
            if (closed) "operation_disabled" else gate(c)
        }
        if (rejection != null) { finish(c, Outcome("rejected", rejection), sink); return }
        val running = result(c, 2, "running", "running")
        synchronized(lock) { ledger.getValue(c.id).result = running }
        emit(sink, Wire.encode(running))
        val outcome = try {
            when (c.operation) {
                "state.get" -> policy().let { p -> Outcome("succeeded", "ok", Wire.obj(
                    "paused" to p.paused, "accessibility" to p.accessibility,
                    "textAllowed" to p.textAllowed, "eventsAllowed" to p.eventsAllowed,
                    "conversationId" to p.conversationId)) }
                "capabilities.get" -> Outcome("succeeded", "ok", Wire.obj(
                    "operations" to Wire.strings(OPERATIONS),
                    "events" to Wire.strings(listOf("message.observed")),
                    "limits" to Wire.obj("frameBytes" to Wire.MAX_FRAME, "batchCommands" to 32,
                        "ledgerEntries" to ledgerLimit, "textUtf16Units" to 4000),
                    "textOutcome" to "ui_click_is_unknown"))
                else -> {
                    val finalGate = synchronized(lock) { if (closed) "operation_disabled" else gate(c) }
                    if (finalGate != null) Outcome("rejected", finalGate)
                    else executor.sendText(c.params["conversationId"].asString, c.params["text"].asString)
                }
            }
        } catch (_: Exception) { Outcome("unknown", "execution_unknown") }
        finish(c, outcome, sink)
    }
    private fun finish(c: Command, outcome: Outcome, sink: FrameSink) {
        val r = try {
            // Validate the complete outbound frame before retaining an unusable terminal result.
            Wire.decodeResult(Wire.encode(result(c, 3, outcome.state, outcome.code, outcome.data)))
        } catch (_: Exception) {
            result(c, 3, "unknown", "invalid_executor_result")
        }
        synchronized(lock) { ledger.getValue(c.id).result = r; pending-- }
        emit(sink, Wire.encode(r))
    }
    private fun result(c: Command, sequence: Int, state: String, code: String, data: JsonObject = JsonObject()) =
        Result(c.id, target, sequence, state, code, data)
    private fun emit(sink: FrameSink, frame: String) {
        // Delivery failure never rolls back or retries a UI action. The ledger remains queryable by duplicate.
        try { sink.send(frame) } catch (_: Exception) { deliveryFailures.incrementAndGet() }
    }
    /** No admission, scheduler, transport or UI action. Returned records contain no payload or identities. */
    fun inspectLocal(): LocalSnapshot = synchronized(lock) {
        val p = try { policy() } catch (_: Exception) { null }
        LocalSnapshot(closed, p != null, closed || p?.paused != false,
            !closed && p?.accessibility == true, !closed && p?.textAllowed == true,
            !closed && p?.eventsAllowed == true, !closed && p?.conversationId != null,
            pending, deliveryFailures.get(), ledger.values.map { localRecord(it) })
    }
    /** recordId is local to this engine instance; it is not a wire command ID or cross-restart key. */
    fun localResult(recordId: Int): LocalCommandRecord? = synchronized(lock) {
        ledger.values.firstOrNull { it.recordId == recordId }?.let { localRecord(it) }
    }
    private fun localRecord(e: Entry) = LocalCommandRecord(e.recordId,
        e.command.operation.takeIf { it in OPERATIONS } ?: "unsupported_operation",
        e.result.sequence, e.result.state,
        e.result.code.takeIf { it in LOCAL_CODES } ?: "executor_result")
    fun close() = synchronized(lock) { closed = true }
    companion object {
        val OPERATIONS = listOf("state.get", "capabilities.get", "text.send")
        // Executor codes and unsupported operation names are untrusted text too.
        private val LOCAL_CODES = setOf("accepted", "running", "ok", "busy", "expired", "paused",
            "permission_denied", "operation_disabled", "unsupported_operation", "policy_unavailable",
            "target_unavailable", "target_ambiguous", "draft_not_empty", "invalid_text",
            "input_unconfirmed", "ui_click_unconfirmed", "execution_unknown", "invalid_executor_result")
    }
}
