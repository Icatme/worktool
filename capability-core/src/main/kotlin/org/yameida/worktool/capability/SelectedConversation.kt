package org.yameida.worktool.capability

import java.util.UUID

/** A locally chosen, still-visible UI surface. Implementations must never search by display name. */
interface TextSurface {
    /** null when the exact selected surface remains valid; otherwise a rejection code. */
    fun check(): String?
    fun draft(): String?
    fun input(text: String): Boolean
    fun clickSend(): Boolean
}

/** Main-thread owned, short-lived and single-use. Navigation/pause/configuration must invalidate it. */
class SelectedConversation(
    private val allowed: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : CapabilityExecutor {
    private data class Binding(val id: String, val surface: TextSurface, val expiresAt: Long)
    private var binding: Binding? = null
    private var generation = 0L
    val conversationId: String?
        get() {
            val b = binding ?: return null
            if (b.expiresAt <= clock() || b.surface.check() != null) { invalidate(); return null }
            return b.id
        }
    fun bindLocally(surface: TextSurface): String? {
        invalidate()
        if (surface.check() != null || surface.draft() != "") return null
        val id = newId().also { require(Wire.validId(it)) }
        binding = Binding(id, surface, clock() + 60_000)
        return id
    }
    fun invalidate() { generation++; binding = null }
    override fun sendText(conversationId: String, text: String): Outcome {
        val b = binding ?: return Outcome("rejected", "target_unavailable")
        if (b.id != conversationId) return Outcome("rejected", "target_unavailable")
        // Consume before any possible action. A new command can never reuse this UI selection.
        val selectedGeneration = generation
        binding = null
        if (b.expiresAt <= clock()) return Outcome("rejected", "target_unavailable")
        if (!allowed()) return Outcome("rejected", "operation_disabled")
        b.surface.check()?.let { return Outcome("rejected", it) }
        if (b.surface.draft() != "") return Outcome("rejected", "draft_not_empty")
        if (!Wire.validText(text)) return Outcome("rejected", "invalid_text")
        // Once input is attempted, even a false return can mean the draft changed.
        return try {
            if (!b.surface.input(text)) return Outcome("unknown", "input_unconfirmed")
            if (generation != selectedGeneration || !allowed() || b.surface.check() != null || b.surface.draft() != text)
                return Outcome("unknown", "input_unconfirmed")
            b.surface.clickSend()
            Outcome("unknown", "ui_click_unconfirmed")
        } catch (_: Exception) { Outcome("unknown", "execution_unknown") }
    }
}
