package org.yameida.worktool.utils

import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.Utils
import org.yameida.worktool.Constant
import org.yameida.worktool.service.WeworkController
import java.io.File

/**
 * 文件分享工具类
 */
object ShareUtil {

    /**
     * Share Text
     */
    const val TEXT = "text/plain"

    /**
     * Share Image
     */
    const val IMAGE = "image/*"

    /**
     * Share Audio
     */
    const val AUDIO = "audio/*"

    /**
     * Share Video
     */
    const val VIDEO = "video/*"

    /**
     * Share File
     */
    const val File = "*/*"

    /**
     * 文件分享 需要先授权显示悬浮窗
     */
    fun share(type: String, path: String): Boolean {
        return share(type, File(path))
    }

    /**
     * 文件分享 需要先授权显示悬浮窗
     */
    fun share(type: String, file: File, auto: Boolean = true): Boolean {
        val app = Utils.getApp()
        if (auto) {
            val root = WeworkController.weworkService.rootInActiveWindow
            if (root?.packageName != Constant.PACKAGE_NAMES) {
                LogUtils.e("文件分享失败 当前应用不在前台")
                return false
            }
            if (!Settings.canDrawOverlays(app)) {
                LogUtils.e("文件分享失败 没有悬浮窗权限~")
                return false
            }
        }
        if (!file.isFile || file.length() <= 0 || file.length() > 25L * 1024 * 1024) {
            LogUtils.e("共享文件不可用或超过 25 MiB")
            return false
        }
        // Only this bounded snapshot is exposed by FileProvider, never the original directory.
        val shareRoot = File(app.cacheDir, "share").apply { mkdirs() }
        val snapshotDir = File(shareRoot, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val shared = File(snapshotDir, file.name)
        try {
            file.inputStream().use { input ->
                shared.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total <= 25L * 1024 * 1024) { "Shared file grew beyond limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (e: Exception) {
            shared.delete()
            snapshotDir.delete()
            LogUtils.e("无法准备共享文件")
            return false
        }
        return try {
            val intent = Intent().apply {
                action = Intent.ACTION_SEND
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                this.type = type
                val fileURI = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", shared, file.name)
                putExtra(Intent.EXTRA_STREAM, fileURI)
                clipData = android.content.ClipData.newRawUri("shared file", fileURI)
            }
            intent.setPackage(Constant.PACKAGE_NAMES)
            app.startActivity(Intent.createChooser(intent, "WorkTool文件分享").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION })
            true
        } catch (e: RuntimeException) {
            shared.delete()
            snapshotDir.delete()
            LogUtils.e("系统未允许打开文件分享，请返回应用重试")
            false
        }
    }

}
