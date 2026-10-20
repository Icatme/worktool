package org.yameida.worktool.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.*
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import org.yameida.worktool.databinding.ActivitySettingsBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import org.yameida.worktool.service.WeworkController
import org.yameida.worktool.utils.*
import java.io.File


/**
 * 设置页
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val rl_bar get() = binding.rlBar
    private val iv_back_left get() = binding.ivBackLeft
    private val rl_reply_strategy get() = binding.rlReplyStrategy
    private val iv_rec_reply_strategy_ get() = binding.ivRecReplyStrategy
    private val tv_select_reply_strategy get() = binding.tvSelectReplyStrategy
    private val rl_rec_orientation get() = binding.rlRecOrientation
    private val iv_rec_orientation_ get() = binding.ivRecOrientation
    private val tv_select_orientation get() = binding.tvSelectOrientation
    private val rl_rec_location get() = binding.rlRecLocation
    private val iv_rec_location_ get() = binding.ivRecLocation
    private val tv_select_location get() = binding.tvSelectLocation
    private val tv_save_location_tip get() = binding.tvSaveLocationTip
    private val rl_encrypt get() = binding.rlEncrypt
    private val iv_encrypt get() = binding.ivEncrypt
    private val sw_encrypt get() = binding.swEncrypt
    private val rl_rec_resolution get() = binding.rlRecResolution
    private val iv_rec_resolution_ get() = binding.ivRecResolution
    private val sw_receive get() = binding.swReceive
    private val tv_select_resolution get() = binding.tvSelectResolution
    private val rl_advance get() = binding.rlAdvance
    private val iv_rec_advance_ get() = binding.ivRecAdvance
    private val tv_select_advance get() = binding.tvSelectAdvance
    private val rl_log get() = binding.rlLog
    private val iv_rec_log_ get() = binding.ivRecLog
    private val tv_select_log get() = binding.tvSelectLog
    private val rl_update get() = binding.rlUpdate
    private val iv_rec_update_ get() = binding.ivRecUpdate
    private val tv_select_update get() = binding.tvSelectUpdate
    private val rl_donate get() = binding.rlDonate
    private val iv_rec_donate_ get() = binding.ivRecDonate
    private val tv_select_donate get() = binding.tvSelectDonate
    private val rl_share get() = binding.rlShare
    private val iv_rec_share_ get() = binding.ivRecShare
    private val tv_select_share get() = binding.tvSelectShare
    private val bt_open_main get() = binding.btOpenMain
    private val bt_open_flow get() = binding.btOpenFlow


    companion object {
        fun enterActivity(context: Context) {
            LogUtils.d("SettingsActivity.enterActivity")
            context.startActivity(Intent(context, SettingsActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)

        initView()
        initData()
    }

    override fun onResume() {
        super.onResume()
        freshOpenFlow()
        freshOpenMain()
    }

    private fun initView() {
        iv_back_left.setOnClickListener { finish() }
        sw_encrypt.isChecked = Constant.encryptType == 1
        sw_encrypt.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_encrypt onCheckedChanged: $isChecked")
            Constant.encryptType = if (isChecked) 1 else 0
            SPUtils.getInstance().put("encryptType", Constant.encryptType)
        })
        sw_receive.isChecked = Constant.autoReply == 1
        sw_receive.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_receive onCheckedChanged: $isChecked")
            Constant.autoReply = if (isChecked) 1 else 0
            SPUtils.getInstance().put("autoReply", Constant.autoReply)
        })
        rl_reply_strategy.setOnClickListener { showReplyStrategyDialog() }
        rl_log.setOnClickListener { showLogDialog() }
        rl_update.setOnClickListener { showUpdateDialog() }
        rl_donate.setOnClickListener { showDonateDialog() }
        rl_share.setOnClickListener { showShareDialog() }
        rl_advance.setOnClickListener { SettingsAdvanceActivity.enterActivity(this) }
        freshOpenFlow()
        bt_open_flow.setOnClickListener {
            freshOpenFlow()
            if (Settings.canDrawOverlays(Utils.getApp())) {
                if (!FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                    ToastUtils.showLong("请同时打开后台弹出界面权限~")
                    PermissionPageManagement.goToSetting(this)
                } else {
                    FloatWindowHelper.showWindow()
                }
            } else {
                startActivity(Intent(this, FloatViewGuideActivity::class.java))
            }
        }
        freshOpenMain()
        bt_open_main.setOnClickListener {
            freshOpenMain()
            if (PermissionHelper.isAccessibilitySettingOn()) {
                if (FlowPermissionHelper.isBlueCloud()) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } else {
                    WeworkController.weworkService.disableSelf()
                    freshOpenMain()
                }
            } else {
                if (Constant.robotId.isBlank()) {
                    ToastUtils.showLong("请先填写并保存链接号~")
                } else if (!PermissionHelper.isAccessibilitySettingOn()) {
                    startActivity(Intent(this, AccessibilityGuideActivity::class.java))
                }
            }
        }
    }

    private fun initData() {
        HttpUtil.getMyConfig()
    }

    private fun showReplyStrategyDialog() {
        val strategyArray = arrayOf("只读消息不回调", "仅私聊和群聊@机器人回调", "私聊群聊全部回调")
        QMUIDialog.CheckableDialogBuilder(this)
            .setTitle("回复策略")
            .addItems(strategyArray) { dialog, which ->
                dialog.dismiss()
                updateRobotReplyStrategy(which)
            }
            .setCheckedIndex(Constant.replyStrategy)
            .create(R.style.QMUI_Dialog)
            .show()
    }

    private fun showLogDialog() {
        val logDir = Utils.getApp().getExternalFilesDir("log")
        if (logDir != null && logDir.exists()) {
            logDir.listFiles()?.forEach {
                if (it.name.endsWith(".snapshot")) it.delete()
            }
            val listFiles = logDir.listFiles()?.filter { it.isFile }.orEmpty()
            if (listFiles.isEmpty()) {
                ToastUtils.showLong("没有可读取的日志文件")
                return
            }
            QMUIDialog.CheckableDialogBuilder(this)
                .setTitle("日志文件分享")
                .addItems(listFiles.map { it.name.replace("_" + AppUtils.getAppPackageName(), "") }.toTypedArray()) { dialog, which ->
                    dialog.dismiss()
                    ToastUtils.showLong(listFiles[which].name)
                    val currentLogFilePath = listFiles[which].absolutePath
                    FileUtils.copy(currentLogFilePath, "$currentLogFilePath.snapshot")
                    ShareUtil.share("*/*", File("$currentLogFilePath.snapshot"), auto = false)
                }
                .create(R.style.QMUI_Dialog)
                .show()
        } else {
            ToastUtils.showLong("日志文件夹为空~")
        }
    }

    private fun showUpdateDialog() {
        if (Constant.getMasterCheckUpdateUrl() == Constant.getCheckUpdateUrl()) {
            HttpUtil.checkUpdate()
        } else {
            QMUIDialog.CheckableDialogBuilder(this)
                .setTitle("检查新版本")
                .addItems(arrayOf("检查当前Host新版本", "检查${getString(R.string.app_name)}官方新版本")) { dialog, which ->
                    dialog.dismiss()
                    if (which == 0) {
                        HttpUtil.checkUpdate()
                    } else {
                        HttpUtil.checkUpdate(Constant.getMasterCheckUpdateUrl())
                    }
                }
                .create(R.style.QMUI_Dialog)
                .show()
        }
    }

    private fun showDonateDialog() {
        DonateUtil.zfbDonate(this)
    }

    private fun showShareDialog() {
        startActivity(Intent.createChooser(Intent().apply {
            action = Intent.ACTION_SEND
            type = ShareUtil.TEXT
            putExtra(Intent.EXTRA_TEXT, "我发现一个非常好用的企业微信机器人程序，文档地址: https://worktool.apifox.cn/ APP下载地址是: https://cdn.asrtts.cn/uploads/worktool/apk/worktool-latest.apk")
        }, "分享"))
    }

    private fun freshOpenFlow() {
        if (Settings.canDrawOverlays(Utils.getApp())) {
            if (FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                bt_open_flow.setBackgroundResource(R.drawable.comment_gray_btn)
                bt_open_flow.text = "悬浮窗权限已开启"
            } else {
                bt_open_flow.setBackgroundResource(R.drawable.comment_red_btn)
                bt_open_flow.text = "开启后台弹出界面"
            }
        } else {
            bt_open_flow.setBackgroundResource(R.drawable.comment_red_btn)
            bt_open_flow.text = "开启悬浮窗权限"
        }
    }

    private fun freshOpenMain() {
        if (PermissionHelper.isAccessibilitySettingOn()) {
            bt_open_main.setBackgroundResource(R.drawable.comment_gray_btn)
            bt_open_main.text = "主功能已开启"
        } else {
            bt_open_main.setBackgroundResource(R.drawable.comment_red_btn)
            bt_open_main.text = "开启主功能"
        }
    }

    private fun updateRobotReplyStrategy(type: Int) {
        try {
            val json = hashMapOf<String, Any>()
            json["robotId"] = Constant.robotId
            json["replyAll"] = type - 1
            val requestBody = GsonUtils.toJson(json).toRequestBody("application/json; charset=utf-8".toMediaType())
            val call = object : HttpCallback() {
                override fun onSuccess(response: HttpResult) {
                    if (runCatching { JSONObject(response.body()).optInt("code") }.getOrNull() == 200) {
                        Constant.replyStrategy = type
                        ToastUtils.showLong("更新成功")
                    } else {
                        onError(response)
                    }
                }

                override fun onError(response: HttpResult) {
                    ToastUtils.showLong("更新失败，请稍后再试~")
                }
            }
            HttpTransport.post(Constant.getRobotUpdateUrl(), requestBody, call)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }
}
