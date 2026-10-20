package org.yameida.worktool.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import org.yameida.worktool.activity.ListenActivity
import org.yameida.worktool.utils.capture.MediaProjectionHolder

class PlayNotifyService : Service() {
    companion object {
        private const val CHANNEL = "authorized_capture"
        private const val NOTIFICATION = 0x1216
        const val STOP = "org.yameida.worktool.STOP_CAPTURE"
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (MediaProjectionHolder.mMediaProjection != null) return START_NOT_STICKY
        val data = intent?.getParcelableExtra<Intent>("data")
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "用户授权截图", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, ListenActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, PlayNotifyService::class.java).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher).setContentTitle("WorkTool 正在进行授权截图")
                .setContentText("点击停止可立即结束本次截图会话")
                .setContentIntent(open).setOngoing(true)
                .addAction(0, "停止截图", stop).build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION, notification)
            val windowManager = getSystemService(WindowManager::class.java)
            val size = if (Build.VERSION.SDK_INT >= 30) {
                windowManager.maximumWindowMetrics.bounds
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                android.graphics.Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }
            val projection = requireNotNull(
                getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            ) { "Screen capture authorization is no longer valid" }
            MediaProjectionHolder.start(projection, size.width(), size.height(), resources.configuration.densityDpi) {
                Constant.enableMediaProject = false
                stopSelf()
            }
            Constant.enableMediaProject = true
        } catch (e: RuntimeException) {
            MediaProjectionHolder.stop()
            stopSelf()
        }
        return START_NOT_STICKY // Never reuse a consent token after process/service restart.
    }

    override fun onDestroy() {
        Constant.enableMediaProject = false
        MediaProjectionHolder.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (Build.VERSION.SDK_INT < 34 && MediaProjectionHolder.mMediaProjection != null) {
            // Older releases have no onCapturedContentResize callback. End this session instead
            // of returning letterboxed images with coordinates that no longer match accessibility.
            MediaProjectionHolder.stop()
            android.widget.Toast.makeText(this, "屏幕配置已变化，请重新授权截图", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
