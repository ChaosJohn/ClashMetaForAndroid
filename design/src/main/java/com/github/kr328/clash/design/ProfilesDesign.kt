package com.github.kr328.clash.design

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import com.github.kr328.clash.design.adapter.ProfileAdapter
import com.github.kr328.clash.design.databinding.DesignProfilesBinding
import com.github.kr328.clash.design.databinding.DialogProfilesMenuBinding
import com.github.kr328.clash.design.databinding.DialogManualConfigMenuBinding
import com.github.kr328.clash.design.databinding.DialogManualExportBinding
import com.github.kr328.clash.design.databinding.DialogManualImportBinding
import com.github.kr328.clash.design.databinding.DialogProfileQrCodeBinding
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.Profile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ProfilesDesign(context: Context) : Design<ProfilesDesign.Request>(context) {
    sealed class Request {
        object UpdateAll : Request()
        object Create : Request()
        data class Active(val profile: Profile) : Request()
        data class Update(val profile: Profile) : Request()
        data class Edit(val profile: Profile) : Request()
        data class Duplicate(val profile: Profile) : Request()
        data class ShareQrCode(val profile: Profile) : Request()
        data class Delete(val profile: Profile) : Request()
        object ManualConfig : Request()
        object ManualProxies : Request()
        object ManualProxyGroups : Request()
        object ManualRules : Request()
        object ManualImport : Request()
        object ManualExport : Request()
        object ManualImportQr : Request()
        object ManualImportImage : Request()
        object ManualImportFile : Request()
        object ManualExportFile : Request()
        object ManualExportQr : Request()
        object ManualExportLink : Request()
    }

    private val binding = DesignProfilesBinding
        .inflate(context.layoutInflater, context.root, false)
    private val adapter = ProfileAdapter(context, this::requestActive, this::showMenu)

    private var allUpdating: Boolean
        get() = adapter.states.allUpdating;
        set(value) {
            adapter.states.allUpdating = value
        }
    private val rotateAnimation : Animation = AnimationUtils.loadAnimation(context, R.anim.rotate_infinite)

    override val root: View
        get() = binding.root

    suspend fun patchProfiles(profiles: List<Profile>) {
        adapter.apply {
            patchDataSet(this::profiles, profiles, id = { it.uuid })
        }

        val updatable = withContext(Dispatchers.Default) {
            profiles.any { it.imported && it.type != Profile.Type.File }
        }

        withContext(Dispatchers.Main) {
            binding.updateView.visibility = if (updatable) View.VISIBLE else View.GONE
        }
    }

    suspend fun requestSave(profile: Profile) {
        showToast(R.string.active_unsaved_tips, ToastDuration.Long) {
            setAction(R.string.edit) {
                requests.trySend(Request.Edit(profile))
            }
        }
    }

    fun updateElapsed() {
        adapter.updateElapsed()
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    private fun showMenu(profile: Profile) {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogProfilesMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog
        binding.profile = profile

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showManualConfigMenu() {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogManualConfigMenuBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

        binding.master = this
        binding.self = dialog

        dialog.setContentView(binding.root)
        dialog.show()
    }


    fun requestManualProxies(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualProxies)
    }

    fun requestManualProxyGroups(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualProxyGroups)
    }

    fun requestManualRules(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualRules)
    }

    fun requestManualImport(dialog: Dialog) {
        dialog.dismiss()

        showManualImportMenu()
    }

    fun requestManualExport(dialog: Dialog) {
        dialog.dismiss()

        showManualExportMenu()
    }

    fun showManualImportMenu() {
        val dialog = AppBottomSheetDialog(context)
        val binding = DialogManualImportBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup, false)

        binding.master = this
        binding.self = dialog

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showManualExportMenu() {
        val dialog = AppBottomSheetDialog(context)

        val binding = DialogManualExportBinding
            .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup, false)

        binding.master = this
        binding.self = dialog

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun requestManualImportQr(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualImportQr)
    }

    fun requestManualImportImage(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualImportImage)
    }

    fun requestManualImportFile(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualImportFile)
    }

    fun requestManualExportFile(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualExportFile)
    }

    fun requestManualExportQr(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualExportQr)
    }

    fun requestManualExportLink(dialog: Dialog) {
        dialog.dismiss()

        requests.trySend(Request.ManualExportLink)
    }

    fun requestUpdateAll() {
        allUpdating = true;
        changeUpdateAllButtonStatus()
        requests.trySend(Request.UpdateAll)
    }

    fun finishUpdateAll() {
        allUpdating = false;
        changeUpdateAllButtonStatus()
    }

    fun requestCreate() {
        requests.trySend(Request.Create)
    }

    fun requestManualConfig() {
        requests.trySend(Request.ManualConfig)
    }

    fun requestManualProxies() {
        requests.trySend(Request.ManualProxies)
    }

    fun requestManualProxyGroups() {
        requests.trySend(Request.ManualProxyGroups)
    }

    fun requestManualRules() {
        requests.trySend(Request.ManualRules)
    }

    private fun requestActive(profile: Profile) {
        requests.trySend(Request.Active(profile))
    }

    fun requestUpdate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Update(profile))

        dialog.dismiss()
    }

    fun requestEdit(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Edit(profile))

        dialog.dismiss()
    }

    fun requestDuplicate(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Duplicate(profile))

        dialog.dismiss()
    }

    fun requestDelete(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.Delete(profile))

        dialog.dismiss()
    }

    fun requestShareQrCode(dialog: Dialog, profile: Profile) {
        requests.trySend(Request.ShareQrCode(profile))

        dialog.dismiss()
    }

    suspend fun showProfileQrCode(profile: Profile) {
        if (profile.type != Profile.Type.Url || profile.source.isBlank()) return

        val bitmap = try {
            withContext(Dispatchers.Default) {
                val matrix = QRCodeWriter().encode(
                    profile.source, BarcodeFormat.QR_CODE, 768, 768,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")
                )
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
        } catch (_: WriterException) {
            showToast(R.string.qr_code_generation_failed, ToastDuration.Long)
            return
        }

        withContext(Dispatchers.Main) {
            val binding = DialogProfileQrCodeBinding.inflate(context.layoutInflater)
            binding.qrCode.setImageBitmap(bitmap)

            suspendCancellableCoroutine<Unit> { continuation ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.share_qr_code)
                    .setView(binding.root)
                    .setPositiveButton(R.string.close) { _, _ -> }
                    .setOnDismissListener {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    .show()

                continuation.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    private fun changeUpdateAllButtonStatus() {
        if (allUpdating) {
            binding.updateView.startAnimation(rotateAnimation)
        } else {
            binding.updateView.clearAnimation()
        }
    }
}
