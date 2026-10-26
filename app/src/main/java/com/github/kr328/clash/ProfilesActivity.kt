package com.github.kr328.clash

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ManualConfig
import com.github.kr328.clash.design.ManualImportDecision
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.decodeQrBitmap
import com.github.kr328.clash.design.renderQrBitmap
import com.github.kr328.clash.design.showManualImportError
import com.github.kr328.clash.design.showManualImportPreview
import com.github.kr328.clash.design.showManualQrCode
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.QRResult.QRError
import io.github.g00fy2.quickie.QRResult.QRMissingPermission
import io.github.g00fy2.quickie.QRResult.QRSuccess
import io.github.g00fy2.quickie.QRResult.QRUserCanceled
import io.github.g00fy2.quickie.ScanQRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    private val scanLauncher =
        registerForActivityResult(ScanQRCode(), ::scanResultHandler)

    private var importConsumer: ((String) -> Unit)? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent(), ::pickImageResultHandler)

    private val exportFileLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/yaml")) { uri ->
            if (uri != null)
                launch { writeManualExport(uri) }
        }

    private val importFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null)
                launch { readManualImport(uri) }
        }

    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported)
                                    setActive(it.profile)
                                else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                        is ProfilesDesign.Request.ShareQrCode ->
                            design.showProfileQrCode(it.profile)
                        ProfilesDesign.Request.ManualConfig ->
                            design.showManualConfigMenu()
                        ProfilesDesign.Request.ManualProxies ->
                            startActivity(ManualProxiesActivity::class.intent)
                        ProfilesDesign.Request.ManualProxyGroups ->
                            startActivity(ManualProxyGroupsActivity::class.intent)
                        ProfilesDesign.Request.ManualRules ->
                            startActivity(ManualRulesActivity::class.intent)
                        ProfilesDesign.Request.ManualImport ->
                            design.showManualImportMenu()
                        ProfilesDesign.Request.ManualExport ->
                            design.showManualExportMenu()
                        ProfilesDesign.Request.ManualImportQr -> {
                            importConsumer = { text ->
                                launch { importManualConfig(design, text) }
                            }
                            scanLauncher.launch(null)
                        }
                        ProfilesDesign.Request.ManualImportImage -> {
                            importConsumer = { text ->
                                launch { importManualConfig(design, text) }
                            }
                            pickImageLauncher.launch("image/*")
                        }
                        ProfilesDesign.Request.ManualImportFile -> {
                            importConsumer = { text ->
                                launch { importManualConfig(design, text) }
                            }
                            importFileLauncher.launch(
                                arrayOf(
                                    "text/yaml", "text/*",
                                    "application/yaml", "application/x-yaml", "*/*",
                                )
                            )
                        }
                        ProfilesDesign.Request.ManualExportFile ->
                            exportManualToFile()
                        ProfilesDesign.Request.ManualExportQr ->
                            exportManualQr(design)
                        ProfilesDesign.Request.ManualExportLink ->
                            exportManualLink(design)
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private fun scanResultHandler(result: QRResult) {
        when (result) {
            is QRSuccess -> {
                val text = result.content.rawValue

                if (text.isNullOrBlank()) {
                    launchScanToast(getString(R.string.manual_import_empty_input))
                    return
                }

                importConsumer?.invoke(text)
            }
            is QRUserCanceled -> Unit
            is QRMissingPermission ->
                launchScanToast(getString(R.string.import_from_qr_no_permission))
            is QRError ->
                launchScanToast(getString(R.string.import_from_qr_exception))
        }
    }

    private fun launchScanToast(message: String) {
        launch {
            design?.showToast(message, ToastDuration.Long)
        }
    }

    private fun pickImageResultHandler(uri: Uri?) {
        if (uri == null) return

        launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    decodeQrFromImage(uri)
                }

                if (text.isNullOrBlank()) {
                    design?.showToast(
                        getString(R.string.manual_import_image_failed),
                        ToastDuration.Long
                    )
                    return@launch
                }

                importConsumer?.invoke(text)
            } catch (e: Exception) {
                design?.showExceptionToast(e)
            }
        }
    }

    /**
     * Decodes a QR code from an image picked from the gallery, via the
     * zxing helper in the design module.
     */
    private fun decodeQrFromImage(uri: Uri): String? {
        val bitmap = android.graphics.BitmapFactory.decodeStream(
            contentResolver.openInputStream(uri)
        ) ?: return null

        return try {
            com.github.kr328.clash.design.decodeQrBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Parses the scanned / decoded / file text and runs the preview +
     * replace/merge flow. The preview runs against the current local
     * manual config and the running tunnel's proxy list (empty when the
     * tunnel is down, in which case only built-ins and the package's own
     * entries resolve).
     */
    private suspend fun importManualConfig(design: ProfilesDesign, text: String) {
        try {
            val draft = withContext(Dispatchers.Default) {
                val (error, config) = Clash.parseManualImport(text)

                error?.let { throw IllegalArgumentException(it) }
                    ?: config ?: throw IllegalArgumentException("empty import package")
            }

            val preview = withClash {
                val proxies = queryAllProxies()

                sanitizeManualImport(
                    draft,
                    queryManualConfig(),
                    proxies.filter { !it.isGroup }.map { mapOf("name" to it.name) },
                    proxies.filter { it.isGroup }.map { mapOf("name" to it.name) },
                )
            } ?: throw IllegalArgumentException("invalid import package")

            val sanitized = preview.sanitized
                ?: throw IllegalArgumentException("invalid import package")

            when (showManualImportPreview(preview)) {
                ManualImportDecision.Dismissed -> return
                ManualImportDecision.Replace -> {
                    withClash { patchManualConfig(sanitized) }

                    design.showToast(R.string.manual_import_applied, ToastDuration.Long)
                }
                ManualImportDecision.Merge -> {
                    withClash {
                        val local = queryManualConfig()

                        patchManualConfig(mergeManualConfig(local, sanitized))
                    }

                    design.showToast(R.string.manual_import_applied, ToastDuration.Long)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showManualImportError(
                    getString(R.string.manual_import_parse_failed, e.message ?: "")
                )
            }
        }
    }

    /**
     * Merge semantics per design Q4: same-name entries (case-insensitive)
     * are overwritten by the imported data, others are appended; rules are
     * appended at the tail.
     */
    private fun mergeManualConfig(local: ManualConfig, imported: ManualConfig): ManualConfig {
        fun <T> mergeLists(existing: List<T>, incoming: List<T>, key: (T) -> String): List<T> {
            val overwritten = incoming.map { key(it).lowercase() }.toSet()

            val kept = existing.filter { key(it).lowercase() !in overwritten }

            return kept + incoming
        }

        return ManualConfig(
            proxies = mergeLists(local.proxies, imported.proxies) { it.name },
            proxyGroups = mergeLists(local.proxyGroups, imported.proxyGroups) { it.name },
            rules = local.rules + imported.rules,
        )
    }

    private suspend fun exportManualToFile() {
        val name = SimpleDateFormat("'cmfa-manual-'yyyyMMdd'.yaml'", Locale.getDefault())
            .format(Date())

        exportFileLauncher.launch(name)
    }

    private suspend fun writeManualExport(uri: Uri) {
        val design = design ?: return

        try {
            val yaml = withClash {
                val config = queryManualConfig()

                if (config.proxies.isEmpty() && config.proxyGroups.isEmpty() && config.rules.isEmpty()) {
                    design.showToast(R.string.manual_export_empty, ToastDuration.Long)
                    return@withClash null
                }

                val (error, result) = Clash.exportManualYAML(config)

                error?.let { throw IllegalArgumentException(it) }

                result
            } ?: return

            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(yaml.toByteArray(Charsets.UTF_8))
                }
            }

            design.showToast(R.string.manual_export_saved, ToastDuration.Long)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun exportManualQr(design: ProfilesDesign) {
        try {
            val bitmap = withClash {
                val config = queryManualConfig()

                if (config.proxies.isEmpty() && config.proxyGroups.isEmpty() && config.rules.isEmpty()) {
                    design.showToast(R.string.manual_export_empty, ToastDuration.Long)
                    return@withClash null
                }

                val (error, uri) = Clash.encodeManualQrPayload(config)

                error?.let {
                    if (it.contains("too large")) {
                        design.showToast(R.string.manual_export_qr_oversize, ToastDuration.Long)
                        return@withClash null
                    }

                    throw IllegalArgumentException(it)
                }

                uri
            } ?: return

            val qr = withContext(Dispatchers.Default) {
                renderQrBitmap(bitmap)
            }

            showManualQrCode(qr)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun exportManualLink(design: ProfilesDesign) {
        try {
            val uri = withClash {
                val config = queryManualConfig()

                if (config.proxies.isEmpty() && config.proxyGroups.isEmpty() && config.rules.isEmpty()) {
                    design.showToast(R.string.manual_export_empty, ToastDuration.Long)
                    return@withClash null
                }

                val (error, result) = Clash.encodeManualQrPayload(config)

                error?.let {
                    if (it.contains("too large")) {
                        design.showToast(R.string.manual_export_qr_oversize, ToastDuration.Long)
                        return@withClash null
                    }

                    throw IllegalArgumentException(it)
                }

                result
            } ?: return

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, uri)
            }

            startActivity(Intent.createChooser(send, getString(R.string.manual_export_link)))
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun readManualImport(uri: Uri) {
        val design = design ?: return

        try {
            val text = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }

            if (text.isNullOrBlank()) {
                design.showToast(R.string.manual_import_empty_input, ToastDuration.Long)
                return
            }

            importManualConfig(design, text)
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            patchProfiles(queryAll())
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
