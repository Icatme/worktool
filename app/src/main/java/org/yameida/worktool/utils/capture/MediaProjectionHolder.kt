package org.yameida.worktool.utils.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One consent token, one VirtualDisplay, multiple frames until the session stops. */
object MediaProjectionHolder {
    private val stateLock = Any()
    private val captureLock = Any()
    @Volatile var mMediaProjection: MediaProjection? = null
        private set
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var callback: MediaProjection.Callback? = null
    private var stopped: (() -> Unit)? = null
    private var density = 1
    private var latestFrame: Bitmap? = null

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int, onStopped: () -> Unit) {
        check(mMediaProjection == null) { "Stop the current projection before requesting new consent" }
        synchronized(stateLock) {
            mMediaProjection = projection
            stopped = onStopped
            density = densityDpi
            val listener = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mMediaProjection === projection) stop()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (mMediaProjection !== projection || width <= 0 || height <= 0) return
                    try {
                        synchronized(stateLock) {
                            if (mMediaProjection !== projection) return
                            if (reader?.width == width && reader?.height == height) return
                            latestFrame?.recycle()
                            latestFrame = null
                            val replacement = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                            try {
                                display?.resize(width, height, density)
                                display?.surface = replacement.surface
                            } catch (e: RuntimeException) {
                                replacement.close()
                                throw e
                            }
                            reader?.close()
                            reader = replacement
                        }
                    } catch (e: RuntimeException) { stop() }
                }
            }
            callback = listener
            try {
                // Registration must precede createVirtualDisplay on target 34+.
                projection.registerCallback(listener, Handler(Looper.getMainLooper()))
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                display = projection.createVirtualDisplay("WorkTool authorized capture",
                    width, height, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader!!.surface, null, null)
            } catch (e: RuntimeException) {
                stop()
                throw e
            }
        }
    }

    /** Worker-thread only. Static screens may not compose another frame; retain the latest one. */
    fun capture(): Bitmap? = synchronized(captureLock) {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val session = mMediaProjection ?: return null
        val deadline = SystemClock.elapsedRealtime() + 1500
        while (SystemClock.elapsedRealtime() < deadline) {
            synchronized(stateLock) {
                if (mMediaProjection !== session) return null
                val image = reader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val plane = image.planes[0]
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                            if (result !== padded) padded.recycle()
                            latestFrame?.recycle()
                            latestFrame = result
                            // Consumers own/recycle their copy; never expose the session's retained bitmap.
                            return result.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (e: RuntimeException) {
                            padded.recycle()
                            throw e
                        }
                    } finally { image.close() }
                }
                latestFrame?.let { return it.copy(Bitmap.Config.ARGB_8888, false) }
            }
            SystemClock.sleep(50)
        }
        null
    }

    fun stop() {
        val projection: MediaProjection?
        val listener: MediaProjection.Callback?
        val notify: (() -> Unit)?
        synchronized(stateLock) {
            projection = mMediaProjection
            listener = callback
            notify = stopped
            mMediaProjection = null
            callback = null
            stopped = null
            display?.release()
            display = null
            reader?.close()
            reader = null
            latestFrame?.recycle()
            latestFrame = null
        }
        if (projection != null) {
            if (listener != null) projection.unregisterCallback(listener)
            projection.stop()
        }
        notify?.invoke()
    }
}
