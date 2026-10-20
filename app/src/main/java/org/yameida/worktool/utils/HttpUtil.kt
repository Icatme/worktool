package org.yameida.worktool.utils

import com.blankj.utilcode.util.*
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import org.yameida.worktool.model.network.CheckUpdateResult
import org.yameida.worktool.model.network.GetMyConfigResult
import org.yameida.worktool.service.log
import org.yameida.worktool.utils.envcheck.CheckRoot
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.yameida.worktool.protocol.UploadOutcome

object HttpUtil {

    /**
     * 检查更新
     */
    fun checkUpdate(url: String? = null) {
        ToastUtils.showLong("企业内部分发版本请联系管理员更新")
    }

    /**
     * 获取机器人配置
     */
    fun getMyConfig(toast: Boolean = true) {
        if (Constant.robotId.isBlank()) {
            if (toast) {
                ToastUtils.showLong("请先填写机器人ID")
            }
            return
        }
        HttpTransport.get(Constant.getMyConfig(), object : HttpCallback() {
                override fun onSuccess(response: HttpResult) {
                    try {
                        val commonResult =
                            GsonUtils.fromJson(
                                response.body(),
                                GetMyConfigResult::class.java
                            )
                        if (commonResult.code != 200) {
                            return onError(response)
                        }
                        LogUtils.i("获取配置", commonResult.data)
                        SPUtils.getInstance().put("risk", false)
                        if (CheckRoot.isDeviceRooted()) {
                            val date = TimeUtils.string2Date(commonResult.data.createTime, "yyyy-MM-dd'T'HH:mm:ss")
                            if (System.currentTimeMillis() - date.time < 15 * 68400 * 1000) {
                                LogUtils.e("环境监测异常，请勿使用本应用！")
                                ToastUtils.showLong("环境监测异常，请勿使用本应用！")
                                SPUtils.getInstance().put("risk", true)
                            }
                        }
                        commonResult.data?.apply {
                            Constant.qaUrl = this.callbackUrl ?: ""
                            Constant.openCallback = this.openCallback ?: 0
                            Constant.replyStrategy = (this.replyAll ?: 0) + 1
                        }
                    } catch (e: Exception) {
                        LogUtils.e(e)
                        onError(response)
                    }
                }

                override fun onError(response: HttpResult) {
                    ToastUtils.showLong("获取配置失败 请检查机器人ID")
                    LogUtils.e("获取配置失败 请检查机器人ID")
                }
            })
    }

    /**
     * 推送图片
     */
    fun pushImage(url: String, titleList: List<String>, receivedName: String?, imagePath: String, roomType: Int) {
        return pushImage(url, titleList, receivedName, File(imagePath).readBytes(), roomType)
    }

    /**
     * 推送图片
     */
    fun pushImage(url: String, titleList: List<String>, receivedName: String?, byteArray: ByteArray, roomType: Int) {
        val json = JSONObject()
        if (receivedName != null) {
            json.put("receivedName", receivedName)
            json.put("groupName", titleList.lastOrNull())
            if (titleList.size > 1) {
                json.put("groupRemark", titleList.first())
            } else {
                json.put("groupRemark", null)
            }
        } else {
            json.put("receivedName", titleList.lastOrNull { !it.contains("＠") } ?: "")
            json.put("groupName", null)
            json.put("groupRemark", null)
        }
        json.put("image", EncodeUtils.base64Encode2String(byteArray))
        json.put("robotId", Constant.robotId)
        json.put("roomType", roomType)
        json.put("atMe", false)
        json.put("textType", 2)
        HttpTransport.postJson(url, json.toString(), object : HttpCallback() {
                override fun onSuccess(response: HttpResult) {
                    LogUtils.d("推送图片成功: ${titleList.joinToString()} $receivedName")
                    log("推送图片成功: ${titleList.joinToString()} $receivedName")
                }

                override fun onError(response: HttpResult) {
                    ToastUtils.showLong("推送图片失败")
                    LogUtils.e("推送图片失败")
                    error("推送图片失败: ${titleList.joinToString()} $receivedName")
                }
            })
    }

    /**
     * 推送本地文件
     */
    fun pushLocalFile(file: File, completion: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        fun finish(success: Boolean) {
            if (completed.compareAndSet(false, true)) completion(success)
        }
        try {
            HttpTransport.postFile(Constant.getPushLocalFileUrl(), file, object : HttpCallback() {
                    override fun onSuccess(response: HttpResult) {
                        val code = try {
                            JSONObject(response.body()).optInt("code", -1)
                        } catch (e: Exception) { null }
                        finish(UploadOutcome.accepted(response.code(), code))
                    }

                    override fun onError(response: HttpResult) {
                        finish(false)
                    }
                })
        } catch (e: Exception) {
            finish(false)
        }
    }
}
