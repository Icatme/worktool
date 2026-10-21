package org.yameida.worktool.capabilities

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.blankj.utilcode.util.SPUtils
import org.yameida.worktool.Constant
import org.yameida.worktool.capability.*
import org.yameida.worktool.protocol.LegacyMode
import org.yameida.worktool.protocol.LocalOverlayGuard
import org.yameida.worktool.protocol.LocalBindingRequest
import org.yameida.worktool.service.WeworkService
import org.yameida.worktool.utils.FloatWindowHelper
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** In-process seam only: no exported component, socket, default host, keys, discovery or reconnect. */
class AndroidCapabilities(private val service: WeworkService) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var channel: Channel? = null
    private var surface: AndroidTextSurface? = null
    private val observer = VisibleMessages()
    private val overlayEvents = LocalOverlayGuard()
    private var bindingGeneration = 0L
    val eventDeliveryFailures = AtomicLong()
    private val selection = SelectedConversation({ canSend() }, android.os.SystemClock::elapsedRealtime)
    val target = Target(deviceId(), UUID.randomUUID().toString())
    private val engine = CommandEngine(target, { localPolicy() }, selection, Executor { task ->
        if (!main.post(task)) throw RejectedExecutionException()
    })
    private fun canSend() = !closed && !LegacyMode.isEnabled() &&
        !FloatWindowHelper.isPause && LocalCapabilities.textAllowed
    private fun localPolicy() = LocalPolicy(
        paused = FloatWindowHelper.isPause, accessibility = !closed,
        textAllowed = !closed && LocalCapabilities.textAllowed,
        eventsAllowed = !closed && LocalCapabilities.eventsAllowed,
        conversationId = selection.conversationId
    )
    /** Local read-only diagnostics; no transport attachment or replay is required. */
    fun inspectLocal(): LocalSnapshot { mainThread(); return engine.inspectLocal() }
    fun localResult(recordId: Int): LocalCommandRecord? { mainThread(); return engine.localResult(recordId) }
    /**
     * A future transport adapter must authenticate externally before creating VerifiedPeer.
     * Must be called on main; there is deliberately no fake production verifier here.
     */
    fun attach(peer: VerifiedPeer, sink: FrameSink): Channel {
        mainThread()
        check(!closed && !LegacyMode.isEnabled())
        require(peer.id.isNotBlank() && peer.target == target)
        channel?.close()
        return Channel(peer.copy(operations = peer.operations.toSet()), sink).also { channel = it; observer.reset() }
    }
    inner class Channel internal constructor(private val peer: VerifiedPeer, private val sink: FrameSink) : AutoCloseable {
        private var disconnected = false
        /** Serialized on main with local controls; a transport must apply its own bounded framing. */
        fun receive(frame: String) {
            mainThread()
            check(!disconnected && !closed)
            engine.receive(frame, peer, FrameSink { if (disconnected) throw IllegalStateException(); sink.send(it) })
        }
        internal fun observed(message: ObservedMessage) {
            if (disconnected || "message.observed" !in peer.operations) return
            try { sink.send(Wire.event(UUID.randomUUID().toString(), target, System.currentTimeMillis(), message)) }
            catch (_: Exception) { eventDeliveryFailures.incrementAndGet() }
        }
        override fun close() {
            mainThread()
            disconnected = true
            if (channel === this) channel = null
            observer.reset()
        }
    }
    /** Called only by a local floating-control gesture, never by a command. */
    fun prepareCurrentChatBinding(): (() -> String?)? {
        mainThread()
        invalidateSelection()
        if (closed || LegacyMode.isEnabled() || (!LocalCapabilities.textAllowed && !LocalCapabilities.eventsAllowed)) return null
        val candidate = AndroidTextSurface.capture(service) ?: return null
        val request = LocalBindingRequest(bindingGeneration, android.os.SystemClock.uptimeMillis())
        return complete@{
            mainThread()
            if (!request.consume(bindingGeneration, android.os.SystemClock.uptimeMillis()) ||
                closed || LegacyMode.isEnabled() ||
                (!LocalCapabilities.textAllowed && !LocalCapabilities.eventsAllowed) ||
                candidate.check() != null) return@complete null
            val id = selection.bindLocally(candidate) ?: return@complete null
            surface = candidate
            if (LocalCapabilities.eventsAllowed && !FloatWindowHelper.isPause)
                candidate.visibleMessages(id)?.let { observer.update(it) }
            id
        }
    }
    fun invalidateSelection() {
        mainThread()
        bindingGeneration++
        clearSelection()
    }
    private fun clearSelection() {
        selection.invalidate(); surface = null; observer.reset()
    }
    fun localPauseChanged() {
        mainThread()
        service.softKeyboardController.showMode = android.accessibilityservice.AccessibilityService.SHOW_MODE_AUTO
        invalidateSelection()
    }
    fun noteLocalOverlayTransition(oldWindowId: Int, newWindowId: Int) {
        mainThread()
        val now = android.os.SystemClock.uptimeMillis()
        overlayEvents.record(oldWindowId, now)
        overlayEvents.record(newWindowId, now)
    }
    fun onEvent(event: AccessibilityEvent) {
        mainThread()
        if (closed) return
        // A pending binding retains its original nodes/header, never recaptures a later chat.
        // Real chat interactions cancel it. Nameless structural events may be our attaching logo;
        // those clear an active selection but are checked against the captured surface on completion.
        if (event.packageName?.toString() == Constant.PACKAGE_NAMES &&
            event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
            bindingGeneration++
        // The binding gesture itself replaces our menu with our logo window. Ignore only
        // those exact short-lived IDs, and only while the selected WeCom surface still matches.
        if (event.eventType in setOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_VIEW_CLICKED) &&
            overlayEvents.matches(event.windowId, android.os.SystemClock.uptimeMillis()) &&
            selection.conversationId != null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> { clearSelection(); return }
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (FloatWindowHelper.isPause || !LocalCapabilities.eventsAllowed || channel == null) { observer.reset(); return }
        val id = selection.conversationId ?: run { invalidateSelection(); return }
        val rows = surface?.visibleMessages(id) ?: run { observer.reset(); return }
        observer.update(rows).forEach { channel?.observed(it) }
    }
    override fun close() {
        mainThread()
        closed = true
        channel?.close()
        engine.close()
        invalidateSelection()
    }
    private fun mainThread() { check(Looper.myLooper() == Looper.getMainLooper()) }
    companion object {
        private fun deviceId(): String {
            val prefs = SPUtils.getInstance()
            val existing = prefs.getString("capability_device_id")
            if (Wire.validId(existing)) return existing
            return UUID.randomUUID().toString().also { prefs.put("capability_device_id", it) }
        }
    }
}
