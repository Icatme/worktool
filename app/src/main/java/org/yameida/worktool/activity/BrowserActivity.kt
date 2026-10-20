package org.yameida.worktool.activity

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.yameida.worktool.databinding.ActivityBrowserBinding
import org.yameida.worktool.R

/**
 * 浏览器页
 */
class BrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBrowserBinding
    private val qmwv get() = binding.qmwv


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)

        initView()
    }

    private fun initView() {
        qmwv.loadUrl("https://wt.asrtts.cn")
    }
}