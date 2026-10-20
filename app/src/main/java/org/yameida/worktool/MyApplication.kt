package org.yameida.worktool

import android.app.Application
import android.content.Intent
import com.blankj.utilcode.util.GsonUtils
import com.blankj.utilcode.util.LogUtils
import com.blankj.utilcode.util.SPUtils
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.blankj.utilcode.util.ToastUtils
import com.tencent.wework.api.IWWAPI
import com.tencent.wework.api.WWAPIFactory
import org.yameida.worktool.config.GlobalException
import org.yameida.worktool.utils.IWWAPIUtil
import org.yameida.worktool.utils.LogUtilsInit

class MyApplication : Application() {

    companion object {

        var iwwapi: IWWAPI? = null
        /**
         * 回到WorkTool首页 需要先授权显示悬浮窗
         */
        fun launchIntent() {
            LogUtils.e("进入WorkTool APP~")
            val app = Utils.getApp()
            app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                app.startActivity(this)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        //初始化工具类
        Utils.init(this)
        //初始化Log工具配置
        LogUtilsInit.init()
        GsonUtils.setGsonDelegate(Gson())
        //初始化 Toast 框架
        //初始化企业微信sdk
        IWWAPIUtil.init(this)
        //设置全局异常捕获重启
        Thread.setDefaultUncaughtExceptionHandler(GlobalException.getInstance())
    }

}