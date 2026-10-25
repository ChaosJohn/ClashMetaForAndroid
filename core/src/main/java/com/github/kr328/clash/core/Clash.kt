package com.github.kr328.clash.core

import com.github.kr328.clash.core.bridge.*
import com.github.kr328.clash.core.model.*
import com.github.kr328.clash.core.util.parseInetSocketAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress

object Clash {
    enum class OverrideSlot {
        Persist, Session
    }

    private val ConfigurationOverrideJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun reset() {
        Bridge.nativeReset()
    }

    fun forceGc() {
        Bridge.nativeForceGc()
    }

    fun suspendCore(suspended: Boolean) {
        Bridge.nativeSuspend(suspended)
    }

    fun queryTunnelState(): TunnelState {
        val json = Bridge.nativeQueryTunnelState()

        return Json.decodeFromString(TunnelState.serializer(), json)
    }

    fun queryTrafficNow(): Traffic {
        return Bridge.nativeQueryTrafficNow()
    }

    fun queryTrafficTotal(): Traffic {
        return Bridge.nativeQueryTrafficTotal()
    }

    fun notifyDnsChanged(dns: List<String>) {
        Bridge.nativeNotifyDnsChanged(dns.toSet().joinToString(separator = ","))
    }

    fun notifyTimeZoneChanged(name: String, offset: Int) {
        Bridge.nativeNotifyTimeZoneChanged(name, offset)
    }

    fun notifyInstalledAppsChanged(uids: List<Pair<Int, String>>) {
        val uidList = uids.joinToString(separator = ",") { "${it.first}:${it.second}" }

        Bridge.nativeNotifyInstalledAppChanged(uidList)
    }

    fun startTun(
        fd: Int,
        stack: String,
        gateway: String,
        portal: String,
        dns: String,
        markSocket: (Int) -> Boolean,
        querySocketUid: (protocol: Int, source: InetSocketAddress, target: InetSocketAddress) -> Int
    ) {
        Bridge.nativeStartTun(fd, stack, gateway, portal, dns, object : TunInterface {
            override fun markSocket(fd: Int) {
                markSocket(fd)
            }

            override fun querySocketUid(protocol: Int, source: String, target: String): Int {
                return querySocketUid(
                    protocol,
                    parseInetSocketAddress(source),
                    parseInetSocketAddress(target)
                )
            }
        })
    }

    fun stopTun() {
        Bridge.nativeStopTun()
    }

    fun startHttp(listenAt: String): String? {
        return Bridge.nativeStartHttp(listenAt)
    }

    fun stopHttp() {
        Bridge.nativeStopHttp()
    }

    fun queryGroupNames(excludeNotSelectable: Boolean): List<String> {
        val names = Json.Default.decodeFromString(
            JsonArray.serializer(),
            Bridge.nativeQueryGroupNames(excludeNotSelectable)
        )

        return names.map {
            require(it.jsonPrimitive.isString)

            it.jsonPrimitive.content
        }
    }

    fun queryAllProxies(): List<AllProxies> {
        return ManualConfigJson.decodeFromString(
            ListSerializer(AllProxies.serializer()),
            Bridge.nativeQueryAllProxies()
        )
    }

    /**
     * The concrete node the main proxy group (Proxy/Proxies,
     * case-insensitive, else the first group) currently selects, resolved
     * through nested groups. Empty when there is no group or selection.
     */
    fun queryProxyNow(): String {
        return Bridge.nativeQueryProxyNow() ?: ""
    }

    fun queryGroup(name: String, sort: ProxySort): ProxyGroup {
        return Bridge.nativeQueryGroup(name, sort.name)
            ?.let { Json.Default.decodeFromString(ProxyGroup.serializer(), it) }
            ?: ProxyGroup("Unknown", emptyList(), "")
    }

    fun healthCheck(name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeHealthCheck(this, name)
        }
    }

    fun healthCheckAll() {
        Bridge.nativeHealthCheckAll()
    }

    fun patchSelector(selector: String, name: String): Boolean {
        return Bridge.nativePatchSelector(selector, name)
    }

    fun fetchAndValid(
        path: File,
        url: String,
        force: Boolean,
        reportStatus: (FetchStatus) -> Unit
    ): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeFetchAndValid(
                object : FetchCallback {
                    override fun report(statusJson: String) {
                        reportStatus(
                            Json.Default.decodeFromString(
                                FetchStatus.serializer(),
                                statusJson
                            )
                        )
                    }

                    override fun complete(error: String?) {
                        if (error != null)
                            completeExceptionally(ClashException(error))
                        else
                            complete(Unit)
                    }
                },
                path.absolutePath,
                url,
                force
            )
        }
    }

    fun load(path: File): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeLoad(this, path.absolutePath)
        }
    }

    fun queryProviders(): List<Provider> {
        val providers =
            Json.Default.decodeFromString(JsonArray.serializer(), Bridge.nativeQueryProviders())

        return List(providers.size) {
            Json.Default.decodeFromJsonElement(Provider.serializer(), providers[it])
        }
    }

    fun updateProvider(type: Provider.Type, name: String): CompletableDeferred<Unit> {
        return CompletableDeferred<Unit>().apply {
            Bridge.nativeUpdateProvider(this, type.toString(), name)
        }
    }

    fun queryOverride(slot: OverrideSlot): ConfigurationOverride {
        return try {
            ConfigurationOverrideJson.decodeFromString(
                ConfigurationOverride.serializer(),
                Bridge.nativeReadOverride(slot.ordinal)
            )
        } catch (e: Exception) {
            ConfigurationOverride()
        }
    }

    fun patchOverride(slot: OverrideSlot, configuration: ConfigurationOverride) {
        Bridge.nativeWriteOverride(
            slot.ordinal,
            ConfigurationOverrideJson.encodeToString(
                ConfigurationOverride.serializer(),
                configuration
            )
        )
    }

    fun clearOverride(slot: OverrideSlot) {
        Bridge.nativeClearOverride(slot.ordinal)
    }

    private val ManualConfigJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun queryManualConfig(): ManualConfig {
        return try {
            ManualConfigJson.decodeFromString(
                ManualConfig.serializer(),
                Bridge.nativeReadManualConfig()
            )
        } catch (e: Exception) {
            ManualConfig()
        }
    }

    fun patchManualConfig(configuration: ManualConfig) {
        Bridge.nativeWriteManualConfig(
            ManualConfigJson.encodeToString(
                ManualConfig.serializer(),
                configuration
            )
        )
    }

    fun clearManualConfig() {
        Bridge.nativeClearManualConfig()
    }

    /**
     * Per-profile dynamic type overrides for subscription proxy groups,
     * keyed by group name. Applied by the Go merger at load time.
     */
    fun queryGroupOverrides(profileId: String): Map<String, ProxyGroupOverride> {
        return try {
            ManualConfigJson.decodeFromString(
                MapSerializer(String.serializer(), ProxyGroupOverride.serializer()),
                Bridge.nativeReadGroupOverrides(profileId)
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun patchGroupOverrides(profileId: String, overrides: Map<String, ProxyGroupOverride>) {
        Bridge.nativeWriteGroupOverrides(
            profileId,
            ManualConfigJson.encodeToString(
                MapSerializer(String.serializer(), ProxyGroupOverride.serializer()),
                overrides
            )
        )
    }

    /**
     * Per-profile dynamic overrides for subscription proxies, keyed by
     * proxy name (currently: dialer-proxy). Applied by the Go merger at
     * load time; the subscription file is never modified.
     */
    fun queryProxyOverrides(profileId: String): Map<String, Map<String, String>> {
        return try {
            ManualConfigJson.decodeFromString(
                MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
                Bridge.nativeReadProxyOverrides(profileId)
            )
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun patchProxyOverrides(profileId: String, overrides: Map<String, Map<String, String>>) {
        Bridge.nativeWriteProxyOverrides(
            profileId,
            ManualConfigJson.encodeToString(
                MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer())),
                overrides
            )
        )
    }

    fun queryConfiguration(): UiConfiguration {
        return Json.Default.decodeFromString(
            UiConfiguration.serializer(),
            Bridge.nativeQueryConfiguration()
        )
    }

    fun subscribeLogcat(): ReceiveChannel<LogMessage> {
        return Channel<LogMessage>(32).apply {
            Bridge.nativeSubscribeLogcat(object : LogcatInterface {
                override fun received(jsonPayload: String) {
                    trySend(Json.decodeFromString(LogMessage.serializer(), jsonPayload))
                }
            })
        }
    }

    fun setAgeSecretKey(key: String?) {
        Bridge.nativeSetAgeSecretKey(key)
    }

    fun genX25519KeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenX25519KeyPair()))
    }

    fun genHybridKeyPair(): AgeKeyPair {
        return parseAgeKeyPair(checkNotNull(Bridge.nativeGenHybridKeyPair()))
    }

    fun veritySecretKeys(vararg secretKeys: String): Boolean {
        return Bridge.nativeVeritySecretKeys(secretKeys.firstOrNull() ?: "")
    }

    fun toPublicKeys(vararg secretKeys: String): List<String> {
        return Bridge.nativeToPublicKeys(secretKeys.firstOrNull() ?: "")
            ?.let { Json.Default.decodeFromString(ListSerializer(String.serializer()), it) }
            ?: emptyList()
    }

    fun verityPublicKeys(vararg publicKeys: String): Boolean {
        return Bridge.nativeVerityPublicKeys(publicKeys.firstOrNull() ?: "")
    }

    private fun parseAgeKeyPair(value: String): AgeKeyPair {
        return Json.Default.decodeFromString(AgeKeyPair.serializer(), value)
    }

    // ------------------------------------------------------------------
    // Manual config exchange (export/import), backed by native manualio.go.
    // Export bridge calls return a JSON envelope with exactly one field
    // set: error / yaml / config / uri.
    // ------------------------------------------------------------------

    private val ManualExchangeJson = Json {
        ignoreUnknownKeys = true
    }

    data class ManualExchangeResult<out T>(val error: String?, val value: T?)

    /**
     * Renders the given manual config as a mihomo-style YAML snippet for
     * file export.
     */
    fun exportManualYAML(configuration: ManualConfig): ManualExchangeResult<String> {
        val raw = Bridge.nativeExportManualYAML(
            ManualConfigJson.encodeToString(ManualConfig.serializer(), configuration)
        )

        val envelope = ManualExchangeJson.decodeFromString(ExchangeEnvelope.serializer(), raw)

        return ManualExchangeResult(envelope.error, envelope.yaml)
    }

    /**
     * Normalizes external input (YAML snippet or cmfa://manual QR URI) into
     * a ManualConfig draft.
     */
    fun parseManualImport(text: String): ManualExchangeResult<ManualConfig> {
        val raw = Bridge.nativeParseManualImport(text)

        val envelope = ManualExchangeJson.decodeFromString(ExchangeEnvelope.serializer(), raw)

        val config = envelope.config?.let {
            ManualConfigJson.decodeFromString(ManualConfig.serializer(), it)
        }

        return ManualExchangeResult(envelope.error, config)
    }

    /**
     * Builds the cmfa://manual share URI for QR display. Fails when the
     * config is empty or exceeds the QR size cap.
     */
    fun encodeManualQrPayload(configuration: ManualConfig): ManualExchangeResult<String> {
        val raw = Bridge.nativeEncodeManualQrPayload(
            ManualConfigJson.encodeToString(ManualConfig.serializer(), configuration)
        )

        val envelope = ManualExchangeJson.decodeFromString(ExchangeEnvelope.serializer(), raw)

        return ManualExchangeResult(envelope.error, envelope.uri)
    }

    /**
     * Validates an import package against the local manual config and the
     * current subscription proxies/groups. Returns the preview document
     * (items, warnings, dropped count) including the sanitized package, or
     * null when the native side rejects the input.
     */
    fun sanitizeManualImport(
        draft: ManualConfig,
        local: ManualConfig,
        subProxies: List<AllProxies>,
        subGroups: List<String>
    ): ManualImportPreview? {
        // Go reads [{name: ...}] arrays for both lists; name is the only
        // field the sanitizer consumes.
        val subProxyJson = ManualConfigJson.encodeToString(
            ListSerializer(MapSerializer(String.serializer(), String.serializer())),
            subProxies.map { mapOf("name" to it.name) }
        )

        val subGroupJson = ManualConfigJson.encodeToString(
            ListSerializer(MapSerializer(String.serializer(), String.serializer())),
            subGroups.map { mapOf("name" to it) }
        )

        val raw = Bridge.nativeSanitizeManualImport(
            ManualConfigJson.encodeToString(ManualConfig.serializer(), draft),
            ManualConfigJson.encodeToString(ManualConfig.serializer(), local),
            subProxyJson,
            subGroupJson
        )

        val envelope = ManualExchangeJson.decodeFromString(SanitizeEnvelope.serializer(), raw)

        return envelope.result
    }
}

@kotlinx.serialization.Serializable
private data class ExchangeEnvelope(
    val error: String? = null,
    val yaml: String? = null,
    val config: String? = null,
    val uri: String? = null,
)

@kotlinx.serialization.Serializable
private data class SanitizeEnvelope(
    val result: ManualImportPreview? = null,
    val error: String? = null,
)