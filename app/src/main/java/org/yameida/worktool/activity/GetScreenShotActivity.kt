package org.yameida.worktool.activity

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.ToastUtils
import org.yameida.worktool.service.PlayNotifyService
import org.yameida.worktool.utils.capture.MediaProjectionHolder

/** Open only from an explicit user action in the visible WorkTool activity. */
class GetScreenShotActivity : AppCompatActivity() {
    companion object {
        fun startCapture(): Bitmap? = MediaProjectionHolder.capture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MediaProjectionHolder.mMediaProjection != null) {
            finish()
            return
        }
        // A restored consent activity already has an outstanding system request.
        if (savedInstanceState != null) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Existing accessibility coordinates refer to the full display. The system still asks consent.
        val consent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        try { startActivityForResult(consent, 100) }
        catch (e: RuntimeException) {
            ToastUtils.showShort("无法打开系统截图授权")
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == Activity.RESULT_OK && data != null) {
            try {
                ContextCompat.startForegroundService(this, Intent(this, PlayNotifyService::class.java)
                    .putExtra("code", resultCode).putExtra("data", data))
            } catch (e: RuntimeException) {
                ToastUtils.showShort("截图服务未启动，请返回应用重新授权")
            }
        }
        finish()
    }
}
