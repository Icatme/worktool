package org.yameida.worktool.utils

import android.os.Handler
import android.os.Looper
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpResult(private val status: Int, private val text: String, val exception: Exception? = null) {
    fun body(): String = text
    fun code(): Int = status
}
abstract class HttpCallback {
    abstract fun onSuccess(response: HttpResult)
    open fun onError(response: HttpResult) {}
}

/** Small app-owned adapter replacing the unavailable OkGo dependency. Uses platform TLS validation. */
object HttpTransport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val main = Handler(Looper.getMainLooper())
    private val engine = org.yameida.worktool.network.HttpEngine(client)
    fun get(url: String, callback: HttpCallback) = enqueue(Request.Builder().url(url).build(), callback)
    fun post(url: String, body: RequestBody, callback: HttpCallback) =
        enqueue(Request.Builder().url(url).post(body).build(), callback)
    fun postJson(url: String, json: String, callback: HttpCallback) =
        post(url, json.toRequestBody("application/json; charset=utf-8".toMediaType()), callback)
    fun postFile(url: String, file: File, callback: HttpCallback) =
        post(url, MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "reviewed-export.txt", file.asRequestBody("application/octet-stream".toMediaType()))
            .build(), callback)

    private fun enqueue(request: Request, callback: HttpCallback) {
        engine.enqueue(request) { result ->
            val response = HttpResult(result.status, result.body, result.error)
            main.post {
                if (response.code() in 200..299) callback.onSuccess(response)
                else callback.onError(response)
            }
        }
    }

    /** Bounded synchronous download for the existing worker-thread operations. */
    fun download(url: String): ByteArray? = try {
        engine.download(Request.Builder().url(url).build(), 25 * 1024 * 1024)
    } catch (e: IOException) { null }
}
