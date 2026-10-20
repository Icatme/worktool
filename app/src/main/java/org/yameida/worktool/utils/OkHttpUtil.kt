package org.yameida.worktool.utils

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object OkHttpUtil {
    // Certificate chains and hostnames are validated by the platform trust store.
    val okHttpClient = OkHttpClient.Builder()
        .readTimeout(20, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS).build()
}
