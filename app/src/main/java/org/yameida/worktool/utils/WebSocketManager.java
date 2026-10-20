package org.yameida.worktool.utils;

import android.util.Log;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.LogUtils;
import com.blankj.utilcode.util.ToastUtils;

import org.yameida.worktool.Constant;
import org.yameida.worktool.model.WeworkMessageBean;
import org.yameida.worktool.model.WeworkMessageListBean;
import org.yameida.worktool.service.WeworkController;
import org.yameida.worktool.protocol.LegacyMode;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class WebSocketManager {

    public static final String HEARTBEAT = "{\"type\":" + WeworkMessageBean.HEART_BEAT + "}";
    private static final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
    public static Map<String, WebSocketManager> webSocketManager = new ConcurrentHashMap<>();
    private static final int reconnectInt = 5000;  //毫秒
    private static final long heartBeatRate = 5;  //秒
    private Map<String, Long> messageIdMap = new ConcurrentHashMap<>();
    private ScheduledFuture task;
    private WebSocket socket;
    private String url;
    private WebSocketListener listener;
    private boolean connecting = false;
    private volatile boolean closed = true;
    private long lastConnectedTime = 0L;

    /** Inert output sink for the default offline mode; does not construct a client or schedule tasks. */
    public WebSocketManager() {}

    public WebSocketManager(String url, WebSocketListener listener) {
        if (!LegacyMode.isEnabled()) return;
        closed = false;
        Log.e(url, "新建链接");
        this.url = url;
        this.listener = listener;
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();
        this.socket = client.newWebSocket(request, listener);
        socket.send("{\"td\":" + System.currentTimeMillis() + "}");
        webSocketManager.put(url, this);
        task = heartCheckStart();
    }

    public void send(WeworkMessageBean msg) {
        send(new WeworkMessageListBean(msg, WeworkMessageListBean.SOCKET_TYPE_MESSAGE_LIST, null, null));
    }

    /** Receipt ACK only. Deduplication belongs to the command queue, not the transport. */
    public synchronized boolean confirm(String messageId) {
        if (closed || !LegacyMode.isEnabled()) return false;
        if (messageId == null || messageId.trim().isEmpty()) return false;
        send(new WeworkMessageListBean(messageId, WeworkMessageListBean.SOCKET_TYPE_MESSAGE_CONFIRM));
        return true;
    }

    public void send(WeworkMessageListBean msg) {
        send(msg, msg.getSocketType() == 2);
    }

    public void send(WeworkMessageListBean msg, boolean log) {
        if (closed || socket == null || !LegacyMode.isEnabled()) return;
        String json = GsonUtils.toJson(msg);
        boolean success = socket.send(json);
        if (log && success)
            LogUtils.d(url, json, "通讯消息发送成功！");
        if (!success)
            LogUtils.e(url, json, "通讯消息发送失败！");
    }

    public void send(String msg) {
        if (closed || socket == null || !LegacyMode.isEnabled()) return;
        boolean success = socket.send(msg);
        LogUtils.e(url, msg, (success ? "通讯消息发送成功！" : "通讯消息发送失败！"));
    }

    public synchronized void close(int code, String reason) {
        closed = true;
        if (task != null) task.cancel(true);
        if (socket == null) return;
        Log.e("url", "task 取消");
        this.socket.close(code, reason);
        if (url != null) webSocketManager.remove(url, this);
        Log.e(url, "链接关闭");
    }

    public static void closeManager() {
        Log.e("SocketManager", "关闭Manager:");
        for (Map.Entry<String, WebSocketManager> e : webSocketManager.entrySet()) {
            e.getValue().close(1000, "closeAll");
        }
        webSocketManager.clear();
        scheduledExecutorService.shutdown();
    }

    public void reConnect() {
        if (closed || !LegacyMode.isEnabled()) return;
        connecting = true;
        Log.e(url, "重连");
        boolean isConnect = false;
        int interval = reconnectInt;
        while (!closed && LegacyMode.isEnabled() && !Thread.currentThread().isInterrupted()) {
            try {
                isConnect = connect();
                if (isConnect) {
                    connecting = false;
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                Thread.sleep(interval);
                if (interval < 600000) {
                    interval *= 2;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        connecting = false;
    }

    private synchronized boolean connect() {
        if (closed || !LegacyMode.isEnabled()) return false;
        if (socket != null) socket.cancel();
        WebSocket s = new OkHttpClient().newWebSocket(new Request.Builder().url(url).build(), listener);
        if (s.send(WebSocketManager.HEARTBEAT)) {
            this.socket = s;
            s.send("{\"td\":" + System.currentTimeMillis() + "}");
            return true;
        }
        s.cancel();
        return false;
    }

    private ScheduledFuture heartCheckStart() {
        lastConnectedTime = System.currentTimeMillis();
        Runnable r = () -> {
            if (closed || !LegacyMode.isEnabled()) return;
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");//设置日期格式
            Log.d(url, "心跳检测" + df.format(new Date()));// new Date()为获取当前系统时间
            if (!connecting && (socket == null || !socket.send(HEARTBEAT))) {
                //断开链接后关闭新消息接收
                WeworkController.INSTANCE.setEnableLoopRunning(false);
                //断开链接后进入重连
                reConnect();
                //重连后刷新连接时间
                lastConnectedTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() % 1000 == 0) {
                socket.send("{\"td\":" + System.currentTimeMillis() + "}");
            }
            if (!Constant.INSTANCE.getEnableMediaProject()) {
                if (System.currentTimeMillis() - lastConnectedTime > heartBeatRate * 3000 && !FloatWindowHelper.INSTANCE.isPause()) {
                    ToastUtils.showShort("机器人运行中 请勿人工操作手机~");
                }
            }
        };

        //每heartBeatRate秒发一次心跳包
        return scheduledExecutorService.scheduleAtFixedRate(r, heartBeatRate, heartBeatRate, TimeUnit.SECONDS);
    }

    public static WebSocketManager getWebSocketManager(String id) {
        return webSocketManager.get(id);
    }
}
