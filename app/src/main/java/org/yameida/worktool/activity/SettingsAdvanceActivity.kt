package org.yameida.worktool.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.*
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import org.yameida.worktool.databinding.ActivitySettingsAdvanceBinding
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.yameida.worktool.Constant
import org.yameida.worktool.R
import org.yameida.worktool.utils.*
import java.util.*


/**
 * 高级选项页
 */
class SettingsAdvanceActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsAdvanceBinding
    private val rl_bar get() = binding.rlBar
    private val iv_back_left get() = binding.ivBackLeft
    private val rl_full_name get() = binding.rlFullName
    private val sw_full_name get() = binding.swFullName
    private val rl_qr_code get() = binding.rlQrCode
    private val sw_qr_code get() = binding.swQrCode
    private val rl_auto_publish get() = binding.rlAutoPublish
    private val sw_auto_publish get() = binding.swAutoPublish
    private val rl_old_device get() = binding.rlOldDevice
    private val sw_old_device get() = binding.swOldDevice
    private val rl_auto_pass_friend_request get() = binding.rlAutoPassFriendRequest
    private val sw_auto_pass_friend_request get() = binding.swAutoPassFriendRequest
    private val rl_qa_url get() = binding.rlQaUrl
    private val tv_select_qa_url get() = binding.tvSelectQaUrl
    private val rl_rec_orientation get() = binding.rlRecOrientation
    private val iv_rec_orientation_ get() = binding.ivRecOrientation
    private val tv_select_orientation get() = binding.tvSelectOrientation
    private val rl_rec_location get() = binding.rlRecLocation
    private val iv_rec_location_ get() = binding.ivRecLocation
    private val tv_select_location get() = binding.tvSelectLocation
    private val tv_save_location_tip get() = binding.tvSaveLocationTip
    private val ll_corp_param get() = binding.llCorpParam
    private val rl_corp_name get() = binding.rlCorpName
    private val tv_select_corp_name get() = binding.tvSelectCorpName
    private val rl_corp get() = binding.rlCorp
    private val rl_agent get() = binding.rlAgent
    private val rl_schema get() = binding.rlSchema
    private val rl_username get() = binding.rlUsername
    private val rl_signature get() = binding.rlSignature
    private val tv_signature get() = binding.tvSignature
    private val rl_language get() = binding.rlLanguage
    private val iv_rec_language_ get() = binding.ivRecLanguage
    private val tv_select_language get() = binding.tvSelectLanguage


    companion object {
        fun enterActivity(context: Context) {
            LogUtils.d("SettingsAdvanceActivity.enterActivity")
            context.startActivity(Intent(context, SettingsAdvanceActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivitySettingsAdvanceBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)

        initView()
        initData()
    }

    private fun initView() {
        iv_back_left.setOnClickListener { finish() }
        sw_full_name.isChecked = Constant.fullGroupName
        sw_full_name.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_full_name onCheckedChanged: $isChecked")
            Constant.fullGroupName = isChecked
        })
        sw_qr_code.isChecked = Constant.groupQrCode
        sw_qr_code.isEnabled = false
        sw_qr_code.text = "群二维码读取暂不可用（需迁移用户授权图片选择）"
        sw_auto_publish.isChecked = Constant.autoPublishComment
        sw_auto_publish.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_auto_publish onCheckedChanged: $isChecked")
            Constant.autoPublishComment = isChecked
        })
        sw_old_device.isChecked = Constant.oldDevice
        sw_old_device.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_old_device onCheckedChanged: $isChecked")
            Constant.oldDevice = isChecked
            updateOldDeviceConfig()
        })
        sw_auto_pass_friend_request.isChecked = Constant.autoPassFriendRequest
        sw_auto_pass_friend_request.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_auto_pass_friend_request onCheckedChanged: $isChecked")
            Constant.autoPassFriendRequest = isChecked
        })
        ll_corp_param.visibility = if (Constant.customLink) View.VISIBLE else View.GONE
        rl_username.visibility = if (Constant.customMP) View.VISIBLE else View.GONE
        rl_qa_url.setOnClickListener { showQaUrlDialog() }
        rl_corp_name.setOnClickListener { showCorpNameDialog() }
        rl_corp.setOnClickListener { showCorpIdDialog() }
        rl_agent.setOnClickListener { showAgentIdDialog() }
        rl_schema.setOnClickListener { showSchemaDialog() }
        rl_username.setOnClickListener { showUserNameDialog() }
        rl_signature.setOnClickListener { showSignatureDialog() }
    }

    private fun initData() {
        HttpUtil.getMyConfig()
    }

    private fun showQaUrlDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("消息回调地址")
            .setPlaceholder("请输入回调接口地址")
            .setDefaultText(Constant.qaUrl)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.delete)) { dialog, index ->
                dialog.dismiss()
                updateRobotQaUrl("")
            }
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    if (text.matches("https?://[^/]+.*".toRegex())) {
                        dialog.dismiss()
                        updateRobotQaUrl(text.toString().trim())
                    } else {
                        ToastUtils.showLong("格式异常！")
                    }
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun updateRobotQaUrl(callbackUrl: String) {
        try {
            val json = hashMapOf<String, Any>()
            json["robotId"] = Constant.robotId
            if (callbackUrl.isEmpty()) {
                json["openCallback"] = 0
            } else {
                json["openCallback"] = 1
                json["callbackUrl"] = callbackUrl
            }
            val requestBody = GsonUtils.toJson(json).toRequestBody("application/json; charset=utf-8".toMediaType())
            val call = object : HttpCallback() {
                override fun onSuccess(response: HttpResult) {
                    if (runCatching { JSONObject(response.body()).optInt("code") }.getOrNull() == 200) {
                        Constant.qaUrl = callbackUrl
                        ToastUtils.showLong(if (callbackUrl.isEmpty()) "关闭成功" else "更新成功")
                    } else {
                        onError(response)
                    }
                }

                override fun onError(response: HttpResult) {
                    ToastUtils.showLong(if (callbackUrl.isEmpty()) "关闭失败，请稍后再试~" else "更新失败，请稍后再试~")
                }
            }
            HttpTransport.post(Constant.getRobotUpdateUrl(), requestBody, call)
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private fun showCorpNameDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("企业名称")
            .setDefaultText(Constant.weworkCorpName)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    dialog.dismiss()
                    Constant.weworkCorpName = text.toString().trim()
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun showCorpIdDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("CorpId")
            .setDefaultText(Constant.weworkCorpId)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    dialog.dismiss()
                    Constant.weworkCorpId = text.toString().trim()
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun showAgentIdDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("AgentId")
            .setDefaultText(Constant.weworkAgentId)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    dialog.dismiss()
                    Constant.weworkAgentId = text.toString().trim()
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun showSchemaDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("Schema")
            .setDefaultText(Constant.weworkSchema)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    dialog.dismiss()
                    Constant.weworkSchema = text.toString().trim()
                    IWWAPIUtil.init(this)
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun showUserNameDialog() {
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("UserName")
            .setDefaultText(Constant.weworkMP)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null) {
                    dialog.dismiss()
                    val username = text.toString().trim()
                    Constant.weworkMP = if (username.endsWith("@app")) username else "$username@app"
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun showSignatureDialog() {
        val signature = AppUtils.getAppSignaturesMD5().firstOrNull()?.replace(":", "")?.lowercase(Locale.ROOT)
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle("Signature")
            .setDefaultText(signature)
            .setInputType(InputType.TYPE_NULL)
            .addAction(getString(R.string.copy)) { dialog, index ->
                dialog.dismiss()
                ClipboardUtils.copyText(signature)
                ToastUtils.showLong("复制成功")
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private fun updateOldDeviceConfig() {
        if (Constant.oldDevice) {
            Constant.LONG_INTERVAL = (Constant.BASE_LONG_INTERVAL * 1.5).toLong()
            Constant.CHANGE_PAGE_INTERVAL = (Constant.BASE_CHANGE_PAGE_INTERVAL * 1.5).toLong()
            Constant.POP_WINDOW_INTERVAL = (Constant.BASE_POP_WINDOW_INTERVAL * 1.5).toLong()
            ToastUtils.showLong("防卡顿模式开启")
        } else {
            Constant.LONG_INTERVAL = Constant.BASE_LONG_INTERVAL
            Constant.CHANGE_PAGE_INTERVAL = Constant.BASE_CHANGE_PAGE_INTERVAL
            Constant.POP_WINDOW_INTERVAL = Constant.BASE_POP_WINDOW_INTERVAL
            ToastUtils.showLong("防卡顿模式关闭")
        }
    }

}
