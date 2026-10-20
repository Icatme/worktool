package org.yameida.worktool.utils

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

object QrDecoder {
    fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance))).text
    }
}
