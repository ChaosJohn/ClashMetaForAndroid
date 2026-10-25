package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.service.data.Selection
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.ILogObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.sendOverrideChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.Json

class ClashManager(private val context: Context) : IClashManager,
    CoroutineScope by CoroutineScope(Dispatchers.IO) {
    private val store = ServiceStore(context)
    private var logReceiver: ReceiveChannel<LogMessage>? = null

    override fun queryTunnelState(): TunnelState {
        return Clash.queryTunnelState()
    }

    override fun queryTrafficTotal(): Long {
        return Clash.queryTrafficTotal()
    }

    override fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String> {
        return Clash.queryGroupNames(excludeNotSelectable)
    }

    override fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup {
        return Clash.queryGroup(name, proxySort)
    }

    override fun queryAllProxies(): List<AllProxies> {
        return Clash.queryAllProxies()
    }

    override fun queryConfiguration(): UiConfiguration {
        return Clash.queryConfiguration()
    }

    override fun queryProviders(): ProviderList {
        return ProviderList(Clash.queryProviders())
    }

    override fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride {
        return Clash.queryOverride(slot)
    }

    override fun patchSelector(group: String, name: String): Boolean {
        return Clash.patchSelector(group, name).also {
            val current = store.activeProfile ?: return@also

            if (it) {
                SelectionDao().setSelected(Selection(current, group, name))
            } else {
                SelectionDao().removeSelected(current, group)
            }
        }
    }

    override fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride) {
        Clash.patchOverride(slot, configuration)

        context.sendOverrideChanged()
    }

    override fun clearOverride(slot: Clash.OverrideSlot) {
        Clash.clearOverride(slot)
    }

    override fun queryManualConfig(): ManualConfig {
        return Clash.queryManualConfig()
    }

    override fun patchManualConfig(configuration: ManualConfig) {
        Clash.patchManualConfig(configuration)

        context.sendOverrideChanged()
    }

    override fun clearManualConfig() {
        Clash.clearManualConfig()

        context.sendOverrideChanged()
    }

    override fun exportManualYAML(configuration: ManualConfig): String {
        val (error, yaml) = Clash.exportManualYAML(configuration)

        error?.let { throw IllegalArgumentException(it) }

        return yaml ?: ""
    }

    override fun parseManualImport(text: String): String {
        val (error, config) = Clash.parseManualImport(text)

        error?.let { throw IllegalArgumentException(it) }

        return config?.let {
            // re-encode through the typed model so the AIDL boundary only
            // ever sees normalized documents
            Json.encodeToString(ManualConfig.serializer(), it)
        } ?: "{}"
    }

    override fun encodeManualQrPayload(configuration: ManualConfig): String {
        val (error, uri) = Clash.encodeManualQrPayload(configuration)

        error?.let { throw IllegalArgumentException(it) }

        return uri ?: ""
    }

    override fun sanitizeManualImport(
        draft: ManualConfig,
        local: ManualConfig,
        subProxies: List<Map<String, String>>,
        subGroups: List<Map<String, String>>
    ): ManualImportPreview? {
        val proxies = subProxies.mapNotNull { it["name"] }
            .map { AllProxies(name = it) }

        val groups = subGroups.mapNotNull { it["name"] }

        return Clash.sanitizeManualImport(draft, local, proxies, groups)
    }

    override fun queryGroupOverrides(profileId: String): Map<String, Map<String, String>> {
        return Clash.queryGroupOverrides(profileId).mapValues { (_, override) ->
            buildMap {
                put("type", override.type)
                putAll(override.options)
            }
        }
    }

    override fun patchGroupOverrides(profileId: String, overrides: Map<String, Map<String, String>>) {
        Clash.patchGroupOverrides(profileId, overrides.mapValues { (_, fields) ->
            com.github.kr328.clash.core.model.ProxyGroupOverride(
                type = fields["type"] ?: "select",
                options = fields - "type",
            )
        })

        context.sendOverrideChanged()
    }

    override fun queryProxyOverrides(profileId: String): Map<String, Map<String, String>> {
        return Clash.queryProxyOverrides(profileId)
    }

    override fun patchProxyOverrides(profileId: String, overrides: Map<String, Map<String, String>>) {
        Clash.patchProxyOverrides(profileId, overrides)

        context.sendOverrideChanged()
    }

    override fun queryActiveProfileId(): String {
        return store.activeProfile?.toString() ?: ""
    }

    override suspend fun healthCheck(group: String) {
        return Clash.healthCheck(group).await()
    }

    override suspend fun updateProvider(type: Provider.Type, name: String) {
        return Clash.updateProvider(type, name).await()
    }

    override fun setLogObserver(observer: ILogObserver?) {
        synchronized(this) {
            logReceiver?.apply {
                cancel()

                Clash.forceGc()
            }

            if (observer != null) {
                logReceiver = Clash.subscribeLogcat().also { c ->
                    launch {
                        try {
                            while (isActive) {
                                observer.newItem(c.receive())
                            }
                        } catch (e: CancellationException) {
                            // intended behavior
                            // ignore
                        } catch (e: Exception) {
                            Log.w("UI crashed", e)
                        } finally {
                            withContext(NonCancellable) {
                                c.cancel()

                                Clash.forceGc()
                            }
                        }
                    }
                }
            }
        }
    }
}