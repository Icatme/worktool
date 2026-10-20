package org.yameida.worktool.service

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.blankj.utilcode.util.EncryptUtils
import com.blankj.utilcode.util.GsonUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPUtils
import com.google.gson.reflect.TypeToken
import okhttp3.WebSocket
import org.yameida.worktool.Constant
import org.yameida.worktool.model.ExecCallbackBean
import org.yameida.worktool.model.WeworkMessageBean
import org.yameida.worktool.model.WeworkMessageListBean
import org.yameida.worktool.utils.FloatWindowHelper
import org.yameida.worktool.protocol.CommandLedger
import java.nio.charset.StandardCharsets

import kotlin.concurrent.thread

object MyLooper {

    private var threadHandler: Handler? = null
    private val deliveries = CommandLedger(4096, 10 * 60 * 1000L)
    private data class QueuedCommand(val identity: String, val message: WeworkMessageBean)

    fun finishDelivery(message: WeworkMessageBean) {
        val index = message.commandIndex ?: return
        deliveries.finish(CommandLedger.identity(message.messageId, index), System.currentTimeMillis())
    }

    val looper = thread {
        LogUtils.i("myLooper starting...")
        Looper.prepare()
        val myLooper = Looper.myLooper()
        if (myLooper != null) {
            threadHandler = object : Handler(myLooper) {
                override fun handleMessage(msg: Message) {
                    val queued = msg.obj as? QueuedCommand
                    val command = queued?.message ?: (msg.obj as WeworkMessageBean)
                    // Mark running before waiting so queue-clear cannot release the paused command.
                    if (queued != null && !deliveries.start(queued.identity)) return
                    while (FloatWindowHelper.isPause) {
                        sleep(Constant.CHANGE_PAGE_INTERVAL)
                    }
                    try {
                        dealWithMessage(command)
                    } catch (e: Exception) {
                        LogUtils.e("Command failed; automatic replay is unsafe", e.javaClass.simpleName)
                        uploadCommandResult(command, ExecCallbackBean.ERROR_ILLEGAL_OPERATION,
                            "执行异常；为避免重复副作用，不自动重试", System.currentTimeMillis())
                        finishDelivery(command)
                    } finally {
                        // File upload completes asynchronously in its result callback.
                        if (command.type != WeworkMessageBean.GET_LOCAL_FILE) finishDelivery(command)
                    }
                }
            }
        } else {
            LogUtils.e("myLooper is null!")
        }
        Looper.loop()
    }

    fun init() {
        LogUtils.i("init myLooper...")
        SPUtils.getInstance("noTipMessage").clear()
        SPUtils.getInstance("lastSyncMessage").clear()
        SPUtils.getInstance("noSyncMessage").clear()
        SPUtils.getInstance("limit").clear()
        SPUtils.getInstance("groupInvite").clear()
        SPUtils.getInstance("lastImage").clear()
        SPUtils.getInstance("myInfo").clear()
    }

    fun getInstance(): Handler {
        while (true) {
            threadHandler?.let { return it }
            LogUtils.e("threadHandler is not ready...")
            sleep(Constant.POP_WINDOW_INTERVAL / 5)
        }
    }

    @Synchronized
    fun onMessage(webSocket: WebSocket?, text: String) {
        val messageList: WeworkMessageListBean<WeworkMessageBean> =
            GsonUtils.fromJson<WeworkMessageListBean<WeworkMessageBean>>(text, object : TypeToken<WeworkMessageListBean<ExecCallbackBean>>(){}.type)
        if (messageList.socketType == WeworkMessageListBean.SOCKET_TYPE_HEARTBEAT) {
            return
        }
        if (messageList.socketType == WeworkMessageListBean.SOCKET_TYPE_MESSAGE_CONFIRM) {
            return
        }
        if (messageList.socketType == WeworkMessageListBean.SOCKET_TYPE_MESSAGE_LIST) {
            require(messageList.encryptType == 0 || messageList.encryptType == 1) { "Unsupported encryption" }
            if (messageList.encryptType == 1) {
                val decryptHexStringAES = EncryptUtils.decryptHexStringAES(
                    messageList.encryptedList,
                    Constant.key,
                    Constant.transformation,
                    Constant.iv
                )
                messageList.list =
                    GsonUtils.fromJson(
                        String(decryptHexStringAES, StandardCharsets.UTF_8),
                        object : TypeToken<ArrayList<ExecCallbackBean>>() {}.type
                    )
            }
            // Gson allocates this constructor-only envelope without field initializers.
            require(!messageList.messageId.isNullOrBlank()) { "Missing messageId" }
            require(messageList.list.isNotEmpty() && messageList.list.size <= 1000) { "Invalid command list size" }
            require(messageList.list.all { it != null && it.type != null }) { "Invalid command" }
            // This ACK confirms parsing/receipt only, never execution success.
            WeworkController.weworkService.webSocketManager.confirm(messageList.messageId)
            commands@ for ((index, message) in messageList.list.withIndex()) {
                message.messageId = messageList.messageId
                message.commandIndex = index
                message.meta = messageList.meta
                message.apiSend = messageList.apiSend
                val identity = CommandLedger.identity(messageList.messageId, index)
                val admission = if (message.type == WeworkMessageBean.ROBOT_QUEUE_CLEAR) {
                    deliveries.reserveControl(identity, System.currentTimeMillis())
                } else deliveries.reserve(identity, System.currentTimeMillis())
                when (admission) {
                    CommandLedger.Admission.DUPLICATE -> continue@commands
                    CommandLedger.Admission.FULL -> {
                        uploadCommandResult(message, ExecCallbackBean.ERROR_ILLEGAL_OPERATION,
                            "指令队列已满，请稍后使用新 messageId 重试", System.currentTimeMillis())
                        continue@commands
                    }
                    CommandLedger.Admission.ACCEPTED -> Unit
                }
                if (message.type == WeworkMessageBean.ROBOT_QUEUE_CLEAR) {
                    deliveries.start(identity)
                    getInstance().removeCallbacksAndMessages(null)
                    deliveries.releaseQueued()
                    uploadCommandResult(message, ExecCallbackBean.SUCCESS, "", System.currentTimeMillis())
                    finishDelivery(message)
                } else if (message.type == WeworkMessageBean.LOOP_RECEIVE_NEW_MESSAGE) {
                    WeworkController.enableLoopRunning = true
                    finishDelivery(message)
                } else {
                    WeworkController.mainLoopRunning = false
                    // No payload/hash-based removeMessages: every list position is independent.
                    val accepted = getInstance().sendMessage(Message.obtain().apply {
                        what = 0
                        obj = QueuedCommand(identity, message)
                    })
                    if (!accepted) {
                        uploadCommandResult(message, ExecCallbackBean.ERROR_ILLEGAL_OPERATION,
                            "执行队列不可用", System.currentTimeMillis())
                        finishDelivery(message)
                    }
                }
                getInstance().removeMessages(WeworkMessageBean.LOOP_RECEIVE_NEW_MESSAGE)
                getInstance().sendMessage(Message.obtain().apply {
                    what = WeworkMessageBean.LOOP_RECEIVE_NEW_MESSAGE
                    obj = WeworkMessageBean().apply { type = WeworkMessageBean.LOOP_RECEIVE_NEW_MESSAGE }
                })
            }
        }
    }

    private fun dealWithMessage(message: WeworkMessageBean) {
        when (message.type) {
            WeworkMessageBean.TYPE_CONSOLE_TOAST -> {
                WeworkController.consoleToast(message as ExecCallbackBean)
            }
            WeworkMessageBean.STOP_AND_GO_HOME -> {
                WeworkController.stopAndGoHome()
            }
            WeworkMessageBean.LOOP_RECEIVE_NEW_MESSAGE -> {
                WeworkController.loopReceiveNewMessage()
            }
            WeworkMessageBean.SEND_MESSAGE -> {
                WeworkController.sendMessage(message)
            }
            WeworkMessageBean.REPLY_MESSAGE -> {
                WeworkController.replyMessage(message)
            }
            WeworkMessageBean.RELAY_MESSAGE -> {
                WeworkController.relayMessage(message)
            }
            WeworkMessageBean.INIT_GROUP -> {
                WeworkController.initGroup(message)
            }
            WeworkMessageBean.UPDATE_GROUP -> {
                WeworkController.updateGroup(message)
            }
            WeworkMessageBean.PUSH_MICRO_DISK_IMAGE -> {
                WeworkController.pushMicroDiskImage(message)
            }
            WeworkMessageBean.PUSH_MICRO_DISK_FILE -> {
                WeworkController.pushMicroDiskFile(message)
            }
            WeworkMessageBean.PUSH_MICROPROGRAM -> {
                WeworkController.pushMicroprogram(message)
            }
            WeworkMessageBean.PUSH_OFFICE -> {
                WeworkController.pushOffice(message)
            }
            WeworkMessageBean.PASS_ALL_FRIEND_REQUEST -> {
                unsupported(message)
            }
            WeworkMessageBean.ADD_FRIEND_BY_PHONE -> {
                WeworkController.addFriendByPhone(message)
            }
            WeworkMessageBean.PUSH_FILE -> {
                WeworkController.pushFile(message)
            }
            WeworkMessageBean.PUSH_LINK -> {
                WeworkController.pushLink(message)
            }
            WeworkMessageBean.RECALL_MESSAGE -> {
                WeworkController.recallMessage(message)
            }
            WeworkMessageBean.RELAY_MULTI_MESSAGE -> {
                WeworkController.relayMultiMessage(message)
            }
            WeworkMessageBean.RELAY_MERGE_MESSAGE -> {
                WeworkController.relayMergeMessage(message)
            }
            WeworkMessageBean.SEND_MULTI_MESSAGE -> {
                WeworkController.sendMultiMessage(message)
            }
            WeworkMessageBean.SEND_MERGE_MESSAGE -> {
                WeworkController.sendMergeMessage(message)
            }
            WeworkMessageBean.SCAN_QR_CODE -> {
                WeworkController.scanQrCode(message)
            }
            WeworkMessageBean.DELETE_CONTACT -> {
                WeworkController.deleteContact(message)
            }
            WeworkMessageBean.DISMISS_GROUP -> {
                WeworkController.dismissGroup(message)
            }
            WeworkMessageBean.ADD_FRIEND_BY_GROUP -> {
                WeworkController.addFriendByGroup(message)
            }
            WeworkMessageBean.MODIFY_GROUP_MEMBER_INFO -> {
                WeworkController.modifyGroupMemberInfo(message)
            }
            WeworkMessageBean.ADD_NEED_DEAL -> {
                WeworkController.addNeedDeal(message)
            }
            WeworkMessageBean.CLOCK_IN -> {
                WeworkController.clockIn(message)
            }
            WeworkMessageBean.SWITCH_CORP -> {
                WeworkController.switchCorp(message)
            }
            WeworkMessageBean.SHOW_GROUP_INFO -> {
                WeworkController.showGroupInfo(message)
            }
            WeworkMessageBean.GET_GROUP_INFO -> {
                WeworkController.getGroupInfo(message)
            }
            WeworkMessageBean.GET_FRIEND_INFO -> {
                WeworkController.getFriendInfo(message)
            }
            WeworkMessageBean.GET_MY_INFO -> {
                WeworkController.getMyInfo(message)
            }
            WeworkMessageBean.GET_RECENT_LIST -> {
                WeworkController.getRecentList(message)
            }
            WeworkMessageBean.GET_ALL_FRIEND_INFO -> {
                WeworkController.getAllFriendInfo(message)
            }
            WeworkMessageBean.GET_ALL_GROUP_INFO -> {
                WeworkController.getAllGroupInfo(message)
            }
            WeworkMessageBean.GET_LOCAL_FILE -> {
                WeworkController.getLocalFile(message)
            }
            WeworkMessageBean.GET_CORP_LIST -> {
                WeworkController.getCorpList(message)
            }
            WeworkMessageBean.ROBOT_CONTROLLER_TEST -> {
                unsupported(message)
            }
            else -> unsupported(message)
        }
    }
    private fun unsupported(message: WeworkMessageBean) {
        uploadCommandResult(message, ExecCallbackBean.ERROR_ILLEGAL_OPERATION,
            "当前客户端未支持该指令", System.currentTimeMillis())
    }
}
