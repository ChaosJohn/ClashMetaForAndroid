package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.github.kr328.clash.design.databinding.DialogManualQrBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Renders [text] as a QR bitmap (zxing core, same style as the profile
 * QR dialog).
 */
fun renderQrBitmap(text: String, size: Int = 768): Bitmap {
    val matrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
    )
    val pixels = IntArray(matrix.width * matrix.height) { index ->
        if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/**
 * Decodes a QR code from a bitmap using the zxing core. Falls back from
 * the hybrid binarizer to the histogram one for low-contrast images.
 * Returns null when no code is found.
 */
fun decodeQrBitmap(bitmap: Bitmap): String? {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val source = RGBLuminanceSource(width, height, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    return try {
        MultiFormatReader().apply { setHints(hints) }
            .decode(BinaryBitmap(HybridBinarizer(source)))
            .text
    } catch (_: NotFoundException) {
        try {
            MultiFormatReader().apply { setHints(hints) }
                .decode(BinaryBitmap(GlobalHistogramBinarizer(source)))
                .text
        } catch (_: NotFoundException) {
            null
        }
    }
}

/**
 * Shows the manual config QR code with a credential notice. Returns when
 * the dialog is dismissed. [onSaveImage] is invoked when the user taps
 * "save to gallery"; the caller decides whether the button is offered.
 */
suspend fun Context.showManualQrCode(bitmap: Bitmap) {
    withContext(Dispatchers.Main) {
        val binding = DialogManualQrBinding.inflate(layoutInflater)
        binding.qrCode.setImageBitmap(bitmap)

        suspendCancellableCoroutine<Unit> { continuation ->
            val dialog = MaterialAlertDialogBuilder(this@showManualQrCode)
                .setTitle(R.string.manual_export_qr)
                .setView(binding.root)
                .setMessage(R.string.manual_qr_contains_credentials)
                .setPositiveButton(R.string.close) { _, _ -> }
                .setOnDismissListener {
                    if (continuation.isActive) continuation.resume(Unit)
                }
                .show()

            continuation.invokeOnCancellation { dialog.dismiss() }
        }
    }
}

/**
 * Simple error dialog used for import failures.
 */
fun Context.showManualImportError(message: String) {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.manual_import)
        .setMessage(message)
        .setPositiveButton(R.string.ok) { _, _ -> }
        .show()
}
