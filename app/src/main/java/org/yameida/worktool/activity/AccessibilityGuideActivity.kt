package org.yameida.worktool.activity

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPUtils
import org.yameida.worktool.databinding.ActivityAccessibilityGuideBinding
import org.yameida.worktool.R
import org.yameida.worktool.utils.PermissionHelper

/**
 * Created by gallon on 2019/7/20.
 * 提示开启无障碍服务权限
 */
class AccessibilityGuideActivity: AppCompatActivity() {
    private lateinit var binding: ActivityAccessibilityGuideBinding
    private val rl_guide_title get() = binding.rlGuideTitle
    private val iv_guide_icon get() = binding.ivGuideIcon
    private val tv_guide_icon get() = binding.tvGuideIcon
    private val ll_guide_permit get() = binding.llGuidePermit
    private val iv_over_finger get() = binding.ivOverFinger
    private val tv_float_allow get() = binding.tvFloatAllow
    private val tv_float_reject get() = binding.tvFloatReject
    private val cb_guide_not get() = binding.cbGuideNot


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessibilityGuideBinding.inflate(layoutInflater)
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
                if (!PermissionHelper.isAccessibilitySettingOn()) {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            } catch (t: Throwable) {}
        }
        tv_float_reject.setOnClickListener {
            finish()
        }
        cb_guide_not.isChecked = SPUtils.getInstance().getBoolean("not_show_accessibility_guide", false)
        cb_guide_not.setOnCheckedChangeListener { buttonView, isChecked ->
            SPUtils.getInstance().put("not_show_accessibility_guide", isChecked)
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
        val accessibilitySettingOn = PermissionHelper.isAccessibilitySettingOn()
        LogUtils.d("PermissionHelper.isAccessibilitySettingOn: $accessibilitySettingOn")
        if (accessibilitySettingOn) {
            finish()
        }
    }

}