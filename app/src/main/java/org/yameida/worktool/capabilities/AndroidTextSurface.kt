package org.yameida.worktool.capabilities

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.yameida.worktool.Constant
import org.yameida.worktool.capability.ObservedMessage
import org.yameida.worktool.capability.TextSurface
import org.yameida.worktool.capability.Wire
import org.yameida.worktool.service.WeworkService

/**
 * Restricted version of the legacy text-input/list patterns. No room search, gesture fallback,
 * navigation, retry, join-group parsing, logging of bodies, or legacy result/HTTP calls.
 * The actual WeCom layout must still pass device acceptance for each deployed version.
 */
internal class AndroidTextSurface private constructor(
    private val service: WeworkService,
    private val editor: AccessibilityNodeInfo,
    private val list: AccessibilityNodeInfo,
    private val windowId: Int,
    private val activity: String,
    private val headerSnapshot: List<String>
) : TextSurface {
    override fun check(): String? {
        return try {
        val nodes = currentNodes() ?: return "target_unavailable"
        val editors = nodes.filter { it.isVisibleToUser && it.isEditable }
        val lists = nodes.filter { it.isVisibleToUser && it.className?.toString() == "android.widget.ListView" }
        when {
            editors.size > 1 || lists.size > 1 -> "target_ambiguous"
            editors.singleOrNull() != editor || lists.singleOrNull() != list -> "target_unavailable"
            !editor.refresh() || !list.refresh() || !editor.isEnabled -> "target_unavailable"
            header(nodes, list) != headerSnapshot -> "target_unavailable"
            else -> null
        }
        } catch (_: Exception) { "target_unavailable" }
    }
    private fun currentNodes(): List<AccessibilityNodeInfo>? {
        val keyguard = service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguard.isKeyguardLocked || service.currentClass != activity) return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != Constant.PACKAGE_NAMES || root.windowId != windowId) return null
        return nodes(root)
    }
    override fun draft(): String? = if (editor.refresh()) editor.text?.toString() ?: "" else null
    override fun input(text: String): Boolean {
        if (check() != null) return false
        return editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }
    override fun clickSend(): Boolean {
        if (check() != null) return false
        val all = currentNodes() ?: return false
        val editorBounds = Rect().also { editor.getBoundsInScreen(it) }
        val listNodes = nodes(list)?.toSet() ?: return false
        val buttons = all.filter { node ->
            if (!node.isVisibleToUser || !node.isEnabled || !node.isClickable ||
                node.text?.toString() != "发送" || node in listNodes) false
            else Rect().also { node.getBoundsInScreen(it) }.let {
                it.bottom > editorBounds.top && it.top < editorBounds.bottom
            }
        }
        // Never choose the first of multiple matches, including a message whose text is "发送".
        return buttons.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }
    fun visibleMessages(conversationId: String): List<ObservedMessage>? {
        if (check() != null) return null
        val messages = mutableListOf<ObservedMessage>()
        for (i in 0 until list.childCount) {
            val row = list.getChild(i) ?: return null
            if (!row.isVisibleToUser) return null
            val containers = nodes(row)?.filter { it.className?.toString() == "android.widget.RelativeLayout" && it.childCount == 2 }
                ?: return null
            val candidates = containers.mapNotNull { container ->
                val first = container.getChild(0) ?: return@mapNotNull null
                val second = container.getChild(1) ?: return@mapNotNull null
                if (!container.isVisibleToUser || !first.isVisibleToUser || !second.isVisibleToUser) return@mapNotNull null
                val leftAvatar = first.className?.toString() == "android.widget.ImageView"
                val rightAvatar = second.className?.toString() == "android.widget.ImageView"
                if (leftAvatar == rightAvatar) return@mapNotNull null
                val body = nodes(if (leftAvatar) second else first) ?: return@mapNotNull null
                if (body.any { it.className?.toString() == "android.widget.ImageView" }) return@mapNotNull null
                val texts = body.filter { it.className?.toString() == "android.widget.TextView" && !it.text.isNullOrBlank() }
                val text = texts.singleOrNull()?.text?.toString() ?: return@mapNotNull null
                if (!texts.single().isVisibleToUser) return@mapNotNull null
                if (!Wire.validText(text)) return@mapNotNull null
                ObservedMessage(conversationId, text, if (leftAvatar) "remote" else "self")
            }
            // Non-text or ambiguous rows reset observation; ignoring them could produce false append events.
            if (candidates.size != 1) return null
            messages.add(candidates.single())
        }
        return messages
    }
    companion object {
        private val activities = setOf(
            "com.tencent.wework.msg.controller.MessageListActivity",
            "com.tencent.wework.msg.controller.ExternalGroupMessageListActivity",
            "com.tencent.wework.msg.controller.ExternalWechatUserMessageListActivity"
        )
        fun capture(service: WeworkService): AndroidTextSurface? {
            if (service.currentClass !in activities) return null
            val root = service.rootInActiveWindow ?: return null
            if (root.packageName?.toString() != Constant.PACKAGE_NAMES) return null
            val all = nodes(root) ?: return null
            val editor = all.filter { it.isVisibleToUser && it.isEditable }.singleOrNull() ?: return null
            val list = all.filter { it.isVisibleToUser && it.className?.toString() == "android.widget.ListView" }.singleOrNull() ?: return null
            val header = header(all, list)
            if (header.isEmpty()) return null
            return AndroidTextSurface(service, editor, list, root.windowId, service.currentClass, header)
        }
        // An additional change detector, never a recipient lookup or unique identity.
        private fun header(all: List<AccessibilityNodeInfo>, list: AccessibilityNodeInfo): List<String> {
            val listBounds = Rect().also { list.getBoundsInScreen(it) }
            return all.filter { it.isVisibleToUser && it.className?.toString() == "android.widget.TextView" &&
                !it.text.isNullOrBlank() && Rect().also { rect -> it.getBoundsInScreen(rect) }.bottom <= listBounds.top
            }.map { it.text.toString() }
        }
        private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo>? {
            val result = mutableListOf<AccessibilityNodeInfo>()
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                if (depth > 20 || result.size >= 1200) return null
                result.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
            return result
        }
    }
}
