package org.yameida.worktool.activity

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.yameida.worktool.databinding.ActivityLoginBinding
import org.yameida.worktool.R
import java.util.*

/**
 * 登录页
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val imageView get() = binding.imageView
    private val linearLayout get() = binding.linearLayout
    private val textView get() = binding.textView
    private val tv_visitor_login get() = binding.tvVisitorLogin


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        org.yameida.worktool.utils.WindowInsetsHelper.apply(binding.root)

        initView()
    }

    private fun initView() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour in 12..17) {
            textView.text = "Afternoon"
        } else if (hour in 18..24 || hour in 0..6) {
            textView.text = "Night"
            imageView.setImageResource(R.drawable.good_night_img)
        }
        tv_visitor_login.setOnClickListener {
            ListenActivity.enterActivity(this, 0)
            finish()
        }
    }
}
