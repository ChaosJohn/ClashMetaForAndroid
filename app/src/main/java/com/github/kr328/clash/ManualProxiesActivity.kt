package com.github.kr328.clash

import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.ManualProxiesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.ManualProxyFormDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class ManualProxiesActivity : BaseActivity<ManualProxiesDesign>() {
    override suspend fun main() {
        val design = ManualProxiesDesign(this)

        setContentDesign(design)

        reload(design)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart) {
                        reload(design)
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ManualProxiesDesign.Request.Create ->
                            editProxy(design, null)

                        is ManualProxiesDesign.Request.Edit ->
                            editProxy(design, it.proxy)

                        is ManualProxiesDesign.Request.Delete ->
                            deleteProxy(design, it.proxy)
                    }
                }
            }
        }
    }

    private suspend fun reload(design: ManualProxiesDesign) {
        withClash {
            design.patchConfig(queryManualConfig())
        }
    }

    private suspend fun editProxy(design: ManualProxiesDesign, initial: ManualProxy?) {
        val config = withClash { queryManualConfig() }
        val existingNames = buildSet {
            config.proxies.forEach { add(it.name.lowercase()) }
            config.proxyGroups.forEach { add(it.name.lowercase()) }
        }

        val result = suspendCoroutine<ManualProxy?> { continuation ->
            ManualProxyFormDialog(
                this,
                SUGGESTED_CIPHERS,
                initial,
                existingNames,
            ).show {
                continuation.resume(it)
            }
        } ?: return

        withClash {
            val current = queryManualConfig()
            val proxies = current.proxies.toMutableList()

            if (initial != null) {
                val index = proxies.indexOfFirst { it.name == initial.name }

                if (index >= 0) proxies[index] = result else proxies.add(result)
            } else {
                proxies.add(result)
            }

            try {
                patchManualConfig(current.copy(proxies = proxies))
            } catch (e: Exception) {
                design.showExceptionToast(e)
            }
        }

        reload(design)
    }

    private suspend fun deleteProxy(design: ManualProxiesDesign, proxy: ManualProxy) {
        val config = withClash { queryManualConfig() }

        val inUse = config.proxyGroups.any { g -> g.proxies.contains(proxy.name) }

        if (inUse) {
            design.showToast(R.string.manual_error_proxy_in_use, ToastDuration.Long)

            return
        }

        withClash {
            patchManualConfig(
                config.copy(proxies = config.proxies.filterNot { it.name == proxy.name })
            )
        }

        reload(design)
    }

    companion object {
        val SUGGESTED_CIPHERS = listOf(
            "aes-128-gcm",
            "aes-192-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
        )
    }
}
