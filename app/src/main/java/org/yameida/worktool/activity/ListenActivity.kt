package org.yameida.worktool.activity

import androidx.core.content.ContextCompat
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.CompoundButton
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.*
import org.yameida.worktool.databinding.ActivityListenBinding
import org.yameida.worktool.*
import android.content.*
import android.os.IBinder
import android.text.InputType
import com.blankj.utilcode.constant.PermissionConstants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qmuiteam.qmui.widget.dialog.QMUIDialog
import org.yameida.worktool.service.PlayNotifyService
import org.yameida.worktool.service.WeworkController
import org.yameida.worktool.service.fastStartActivity
import org.yameida.worktool.utils.*
import org.yameida.worktool.utils.capture.MediaProjectionHolder
import org.yameida.worktool.utils.envcheck.CheckHook
import org.yameida.worktool.utils.envcheck.CheckRoot
import org.yameida.worktool.protocol.LegacyMode
import org.yameida.worktool.capabilities.LocalCapabilities


class ListenActivity : AppCompatActivity() {
    private lateinit var binding: ActivityListenBinding
    private val rl_bar get() = binding.rlBar
    private val iv_settings get() = binding.ivSettings
    private val tv_host get() = binding.tvHost
    private val tv_version get() = binding.tvVersion
    private val tv_work_version get() = binding.tvWorkVersion
    private val sw_accessibility get() = binding.swAccessibility
    private val sw_overlay get() = binding.swOverlay
    private val et_channel get() = binding.etChannel
    private val bt_save get() = binding.btSave


    var riskRetry: Int = 0

    companion object {
        /**
         * @param type 0=游客登录
         */
        fun enterActivity(context: Context, type: Int) {
            LogUtils.d("ListenActivity.enterActivity type: $type")
            context.startActivity(Intent(context, ListenActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra("type", type)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityListenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)

        initView()
        initCapabilities()
        initAccessibility()
        initOverlays()
        initData()
        initNotification()
        ContextCompat.registerReceiver(this, openWsReceiver, IntentFilter(Constant.WEWORK_NOTIFY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(openWsReceiver)
    }

    override fun onResume() {
        super.onResume()
        LocalCapabilities.current?.invalidateSelection()
        binding.swCapabilityPause.isChecked = FloatWindowHelper.isPause
        updateModeViews()
        sw_overlay.isChecked = Settings.canDrawOverlays(Utils.getApp()) && FlowPermissionHelper.canBackgroundStart(Utils.getApp())
        sw_accessibility.isChecked = PermissionHelper.isAccessibilitySettingOn()
        if (needToWork) {
            needToWork = false
            goToWork()
        }
    }

    private fun initView() {
        iv_settings.setOnClickListener {
            if (LegacyMode.isEnabled()) SettingsActivity.enterActivity(this)
            else ToastUtils.showShort(R.string.capability_legacy_settings)
        }
        et_channel.setText(Constant.robotId)
        bt_save.setOnClickListener {
            if (!LegacyMode.isEnabled()) return@setOnClickListener
            val channel = et_channel.text.toString().trim()
            Constant.robotId = channel
            ToastUtils.showLong("保存成功")
            sendBroadcast(Intent(Constant.WEWORK_NOTIFY).setPackage(packageName).apply {
                putExtra("type", "modify_channel")
            })
            HttpUtil.getMyConfig(toast = false)
        }
        tv_host.text = Constant.host
        tv_host.setOnClickListener {
            showSelectHostDialog()
        }
        tv_host.setOnLongClickListener {
            showInputHostDialog()
            true
        }
        val version = "${AppUtils.getAppVersionName()}     Android ${DeviceUtils.getSDKVersionName()} ${DeviceUtils.getManufacturer()} ${DeviceUtils.getModel()}"
        val deviceRooted = CheckRoot.isDeviceRooted()
        val hook = CheckHook.isHook(applicationContext)
        if (hook) {
            tv_version.text = "当前设备存在侵入代码，请勿在本设备使用本程序！！！"
        } else if (deviceRooted) {
            tv_version.text = "$version\n本设备已Root，存在一定风险！"
        } else {
            tv_version.text = version
        }
        val workVersionName = AppUtils.getAppInfo(Constant.PACKAGE_NAMES)?.versionName
        when (workVersionName) {
            null -> {
                LogUtils.e("系统检测到您尚未安装企业微信，请先安装企业微信")
                tv_work_version.text = "检测到您尚未安装企业微信，请先安装登录!"
            }
            in Constant.AVAILABLE_VERSION -> {
                LogUtils.i("企业微信历史适配记录: $workVersionName")
                val tip = "$workVersionName   历史适配记录；本次独立能力尚待设备验收"
                tv_work_version.text = tip
                Constant.version = Constant.AVAILABLE_VERSION_MAP[workVersionName] ?: Int.MAX_VALUE
            }
            else -> {
                LogUtils.e("当前企业微信版本未兼容: $workVersionName")
                val tip = "$workVersionName   可能存在部分兼容性问题!"
                tv_work_version.text = tip
            }
        }
        LogUtils.i("Constant.version: ${Constant.version}")
        SPUtils.getInstance().put("appVersion", version)
        SPUtils.getInstance().put("workVersion", workVersionName)
        SPUtils.getInstance().put("deviceRooted", deviceRooted)
        SPUtils.getInstance().put("hook", hook)
    }

    private fun initAccessibility() {
        sw_accessibility.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_accessibility onCheckedChanged: $isChecked")
            if (isChecked) {
                if (LegacyMode.isEnabled() && Constant.robotId.isBlank()) {
                    sw_accessibility.isChecked = false
                    ToastUtils.showLong("请先填写并保存链接号~")
                } else if (!PermissionHelper.isAccessibilitySettingOn()) {
                    if (SPUtils.getInstance().getBoolean("risk", false)) {
                        if (riskRetry > 10) {
                            ToastUtils.showLong("再点${20 - riskRetry}次 允许本次运行")
                        } else {
                            ToastUtils.showLong("环境监测异常，请勿使用本应用！")
                        }
                        if (++riskRetry > 20) {
                            SPUtils.getInstance().put("risk", false)
                            startActivity(Intent(this, AccessibilityGuideActivity::class.java))
                            ToastUtils.showLong("风险提示：临时允许本次运行")
                        }
                        sw_accessibility.isChecked = false
                    } else {
                        startActivity(Intent(this, AccessibilityGuideActivity::class.java))
                    }
                }
            } else {
                if (PermissionHelper.isAccessibilitySettingOn()) {
                    if (FlowPermissionHelper.isBlueCloud()) {
                        sw_accessibility.isChecked = true
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        startActivity(intent)
                    } else {
                        WeworkController.weworkService.disableSelf()
                    }
                }
            }
        })
    }

    private fun initOverlays() {
        sw_overlay.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { buttonView, isChecked ->
            LogUtils.i("sw_overlay onCheckedChanged: $isChecked")
            if (isChecked) {
                startActivity(Intent(this, FloatViewGuideActivity::class.java))
            } else {
                if (Settings.canDrawOverlays(Utils.getApp()) && FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                    sw_overlay.isChecked = true
                    PermissionPageManagement.goToSetting(this)
                }
            }
        })
        if (Settings.canDrawOverlays(Utils.getApp()) && FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
            FloatWindowHelper.showWindow()
        }
    }

    private fun initData() {
        if (LegacyMode.isEnabled()) HttpUtil.getMyConfig(toast = false)
        CacheUtil.autoDelete()
    }

    private fun initCapabilities() {
        binding.swLegacy.isChecked = LegacyMode.isEnabled()
        binding.swCapabilityText.isChecked = LocalCapabilities.textAllowed
        binding.swCapabilityEvents.isChecked = LocalCapabilities.eventsAllowed
        binding.swCapabilityPause.isChecked = FloatWindowHelper.isPause
        binding.swLegacy.setOnCheckedChangeListener { _, enabled ->
            if (!LegacyMode.configure(enabled)) {
                binding.swLegacy.isChecked = LegacyMode.isEnabled()
                ToastUtils.showLong(R.string.capability_mode_locked)
            }
            updateModeViews()
        }
        binding.swCapabilityText.setOnCheckedChangeListener { _, enabled ->
            LocalCapabilities.configure(enabled, binding.swCapabilityEvents.isChecked)
        }
        binding.swCapabilityEvents.setOnCheckedChangeListener { _, enabled ->
            LocalCapabilities.configure(binding.swCapabilityText.isChecked, enabled)
        }
        binding.swCapabilityPause.setOnCheckedChangeListener { _, paused ->
            FloatWindowHelper.isPause = paused
            LocalCapabilities.current?.localPauseChanged()
        }
        binding.btCapabilityInspect.setOnClickListener { showLocalCapabilityState() }
        updateModeViews()
    }

    private fun showLocalCapabilityState() {
        val snapshot = LocalCapabilities.current?.inspectLocal()
        fun flag(value: Boolean) = getString(if (value) R.string.capability_yes else R.string.capability_no)
        val message = if (snapshot == null) getString(R.string.capability_inspection_unavailable) else {
            val records = snapshot.records.takeLast(50).asReversed()
            getString(R.string.capability_local_state,
                flag(snapshot.closed), flag(snapshot.policyAvailable), flag(snapshot.paused),
                flag(snapshot.accessibility), flag(snapshot.textAllowed), flag(snapshot.eventsAllowed),
                flag(snapshot.selectionBound), snapshot.pending, snapshot.deliveryFailures) + "\n\n" +
                getString(R.string.capability_local_records, records.size, snapshot.records.size) + "\n" +
                if (records.isEmpty()) getString(R.string.capability_no_records) else
                    records.joinToString("\n") { record ->
                        getString(R.string.capability_local_record, record.recordId, record.operation,
                            record.state, record.code)
                    }
        }
        MaterialAlertDialogBuilder(this, R.style.Theme_MaterialComponents_DayNight_Dialog)
            .setTitle(R.string.capability_inspect)
            .setMessage(message + "\n\n" + getString(R.string.capability_inspection_scope))
            .setPositiveButton(R.string.capability_refresh) { _, _ -> showLocalCapabilityState() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun updateModeViews() {
        val legacy = LegacyMode.isEnabled()
        binding.tvCapabilityStatus.setText(if (legacy) R.string.capability_legacy_status else R.string.capability_default_status)
        binding.swLegacy.isEnabled = !LegacyMode.isLocked()
        binding.swCapabilityText.isEnabled = !legacy
        binding.swCapabilityEvents.isEnabled = !legacy
        binding.swCapabilityPause.isEnabled = !legacy
        binding.btCapabilityInspect.isEnabled = !legacy
        et_channel.isEnabled = legacy
        bt_save.isEnabled = legacy
        tv_host.isEnabled = legacy
    }

    private fun initNotification() {
        binding.btCapture.setOnClickListener {
            if (MediaProjectionHolder.mMediaProjection != null) {
                stopService(Intent(this, PlayNotifyService::class.java))
            } else {
                MaterialAlertDialogBuilder(this, R.style.Theme_MaterialComponents_DayNight_Dialog)
                    .setTitle("授权屏幕截图")
                    .setMessage("本次会话可连续截图。屏幕可能包含敏感信息；可随时从系统或通知停止。默认不上传图片。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续") { _, _ ->
                        if (Build.VERSION.SDK_INT >= 33 &&
                            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                        } else startActivity(Intent(this, GetScreenShotActivity::class.java))
                    }.show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification denial does not grant or deny screen capture; Android asks separately.
        if (requestCode == 101) startActivity(Intent(this, GetScreenShotActivity::class.java))
    }

    private fun showSelectHostDialog() {
        val hostList = SPUtils.getInstance().getStringSet("host_list", mutableSetOf(Constant.host))
        if (hostList.isNotEmpty()) {
            val hostArray = hostList.toTypedArray()
            QMUIDialog.CheckableDialogBuilder(this)
                .setTitle(getString(R.string.host_list))
                .addItems(hostArray) { dialog, which ->
                    Constant.host = hostArray[which]
                    tv_host.text = hostArray[which]
                    HostTestHelper.testWs()
                    dialog.dismiss()
                }
                .setCheckedIndex(hostList.indexOf(Constant.host))
                .create(R.style.QMUI_Dialog)
                .show()
        }
    }

    private fun showInputHostDialog() {
        ToastUtils.showLong("请输入专线网络")
        val builder = QMUIDialog.EditTextDialogBuilder(this)
        builder.setTitle(getString(R.string.tip))
            .setPlaceholder(getString(R.string.input_new_host))
            .setDefaultText(tv_host.text)
            .setInputType(InputType.TYPE_CLASS_TEXT)
            .addAction(getString(R.string.delete)) { dialog, index ->
                val hostList = SPUtils.getInstance().getStringSet("host_list", mutableSetOf(Constant.host))
                if (hostList.size > 1) {
                    hostList.remove(Constant.host)
                    Constant.host = hostList.elementAt(0)
                    tv_host.text = Constant.host
                    HostTestHelper.testWs()
                    SPUtils.getInstance().put("host_list", hostList)
                    dialog.dismiss()
                } else {
                    ToastUtils.showLong("至少保留一个host！")
                }
            }
            .addAction(getString(R.string.cancel)) { dialog, index -> dialog.dismiss() }
            .addAction(getString(R.string.add)) { dialog, index ->
                val text = builder.editText.text
                if (text != null && text.isNotEmpty()) {
                    if (text.matches("ws{1,2}://[^/]+.*".toRegex())) {
                        val hostList = SPUtils.getInstance().getStringSet("host_list", mutableSetOf(Constant.host))
                        hostList.add(text.toString())
                        SPUtils.getInstance().put("host_list", hostList)
                        Constant.host = text.toString()
                        tv_host.text = text
                        HostTestHelper.testWs()
                        dialog.dismiss()
                    } else {
                        ToastUtils.showLong("格式异常！")
                    }
                } else {
                    ToastUtils.showLong("请勿为空！")
                }
            }
            .create(R.style.QMUI_Dialog).show()
    }

    private var needToWork = false

    private fun goToWork() {
        val positiveButton =
            MaterialAlertDialogBuilder(this, R.style.Theme_MaterialComponents_DayNight_Dialog)
                .setTitle("设置成功")
                .setMessage("请勿人工操作手机     \n5秒后自动跳转")
                .setNegativeButton("", null)
                .setPositiveButton("", null)
        val show = positiveButton.show()
        bt_save.postDelayed({ show.dismiss() }, 5000)
        bt_save.postDelayed({
            packageManager.getLaunchIntentForPackage(Constant.PACKAGE_NAMES)?.apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(this)
            }
        }, 5000)
    }

    private val openWsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("type") == "openWs") {
                needToWork = intent.getBooleanExtra("switch", false)
            }
        }
    }

}
