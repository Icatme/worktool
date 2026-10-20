package org.yameida.worktool.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPUtils
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.Utils
import org.yameida.worktool.databinding.ActivityFloatGuideBinding
import org.yameida.worktool.R
import org.yameida.worktool.utils.FloatWindowHelper
import org.yameida.worktool.utils.FlowPermissionHelper
import org.yameida.worktool.utils.PermissionPageManagement

/**
 * Created by gallon on 2019/7/20.
 * 提示开启悬浮窗权限
 */
class FloatViewGuideActivity: AppCompatActivity() {
    private lateinit var binding: ActivityFloatGuideBinding
    private val rl_guide_title get() = binding.rlGuideTitle
    private val iv_guide_icon get() = binding.ivGuideIcon
    private val tv_guide_icon get() = binding.tvGuideIcon
    private val ll_guide_permit get() = binding.llGuidePermit
    private val iv_over_finger get() = binding.ivOverFinger
    private val tv_float_allow get() = binding.tvFloatAllow
    private val tv_float_reject get() = binding.tvFloatReject
    private val cb_guide_not get() = binding.cbGuideNot


    private var goToSetting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFloatGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LOW_PROFILE or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        tv_float_allow.setOnClickListener {
            try {
                if (!Settings.canDrawOverlays(Utils.getApp())) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (t: Throwable) {}
        }
        tv_float_reject.setOnClickListener {
            finish()
        }
        cb_guide_not.isChecked = SPUtils.getInstance().getBoolean("not_show_float_guide", false)
        cb_guide_not.setOnCheckedChangeListener { buttonView, isChecked ->
            SPUtils.getInstance().put("not_show_float_guide", isChecked)
        }

        val alphaAnimation = AlphaAnimation(0.2F, 1F).apply {
            duration = 800
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        iv_over_finger.startAnimation(alphaAnimation)
    }

    override fun onResume() {
        super.onResume()
        val canDrawOverlays = Settings.canDrawOverlays(Utils.getApp())
        LogUtils.d("Settings.canDrawOverlays: $canDrawOverlays")
        if (canDrawOverlays) {
            if (goToSetting) {
                if (FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                    FloatWindowHelper.showWindow()
                }
                finish()
            } else if (!FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                ToastUtils.showLong("请同时打开后台弹出界面权限~")
                PermissionPageManagement.goToSetting(this)
                goToSetting = true
            } else {
                if (FlowPermissionHelper.canBackgroundStart(Utils.getApp())) {
                    FloatWindowHelper.showWindow()
                }
                finish()
            }
        }
    }

}