package com.github.kr328.clash.service.remote

import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.*
import com.github.kr328.kaidl.BinderInterface

@BinderInterface
interface IClashManager {
    fun queryTunnelState(): TunnelState
    fun queryTrafficTotal(): Long
    fun queryProxyGroupNames(excludeNotSelectable: Boolean): List<String>
    fun queryProxyGroup(name: String, proxySort: ProxySort): ProxyGroup
    fun queryAllProxies(): List<AllProxies>
    fun queryConfiguration(): UiConfiguration
    fun queryProviders(): ProviderList

    fun patchSelector(group: String, name: String): Boolean

    suspend fun healthCheck(group: String)
    suspend fun updateProvider(type: Provider.Type, name: String)

    fun queryOverride(slot: Clash.OverrideSlot): ConfigurationOverride
    fun patchOverride(slot: Clash.OverrideSlot, configuration: ConfigurationOverride)
    fun clearOverride(slot: Clash.OverrideSlot)

    fun queryManualConfig(): ManualConfig
    fun patchManualConfig(configuration: ManualConfig)
    fun clearManualConfig()

    fun queryGroupOverrides(profileId: String): Map<String, Map<String, String>>
    fun patchGroupOverrides(profileId: String, overrides: Map<String, Map<String, String>>)

    /** UUID string of the active profile, empty when none is active. */
    fun queryActiveProfileId(): String

    fun setLogObserver(observer: ILogObserver?)
}