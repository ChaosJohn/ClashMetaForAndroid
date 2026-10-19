package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroupOverride
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.GroupOverrideFormDialog
import com.github.kr328.clash.design.dialog.ProxyDialerOverrideDialog
import com.github.kr328.clash.design.model.ProxyState
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ProxyActivity : BaseActivity<ProxyDesign>() {
    override suspend fun main() {
        val mode = withClash { queryOverride(Clash.OverrideSlot.Session).mode }
        val names = withClash { queryProxyGroupNames(uiStore.proxyExcludeNotSelectable) }
        val states = List(names.size) { ProxyState("?") }
        val unorderedStates = names.indices.map { names[it] to states[it] }.toMap()
        val reloadLock = Semaphore(10)

        val design = ProxyDesign(
            this,
            mode,
            names,
            uiStore
        )

        setContentDesign(design)

        design.requests.send(ProxyDesign.Request.ReloadAll)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ProfileLoaded -> {
                            val newNames = withClash {
                                queryProxyGroupNames(uiStore.proxyExcludeNotSelectable)
                            }

                            if (newNames != names) {
                                startActivity(ProxyActivity::class.intent)

                                finish()
                            }
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProxyDesign.Request.ReLaunch -> {
                            startActivity(ProxyActivity::class.intent)

                            finish()
                        }
                        ProxyDesign.Request.ReloadAll -> {
                            names.indices.forEach { idx ->
                                design.requests.trySend(ProxyDesign.Request.Reload(idx))
                            }
                        }
                        is ProxyDesign.Request.Reload -> {
                            launch {
                                val group = reloadLock.withPermit {
                                    withClash {
                                        queryProxyGroup(names[it.index], uiStore.proxySort)
                                    }
                                }
                                val state = states[it.index]

                                state.now = group.now

                                design.updateGroup(
                                    it.index,
                                    group.proxies,
                                    group.type == "Selector",
                                    state,
                                    unorderedStates
                                )
                            }
                        }
                        is ProxyDesign.Request.Select -> {
                            withClash {
                                patchSelector(names[it.index], it.name)

                                states[it.index].now = it.name
                            }

                            design.requestRedrawVisible()
                        }
                        is ProxyDesign.Request.UrlTest -> {
                            launch {
                                withClash {
                                    healthCheck(names[it.index])
                                }

                                design.requests.send(ProxyDesign.Request.Reload(it.index))
                            }
                        }
                        is ProxyDesign.Request.OverrideGroupType -> {
                            launch {
                                overrideGroupType(names, design, it.index)
                            }
                        }
                        is ProxyDesign.Request.OverrideProxyDialer -> {
                            launch {
                                overrideProxyDialer(design, it.name)
                            }
                        }
                        is ProxyDesign.Request.PatchMode -> {
                            design.showModeSwitchTips()

                            withClash {
                                val o = queryOverride(Clash.OverrideSlot.Session)

                                o.mode = it.mode

                                patchOverride(Clash.OverrideSlot.Session, o)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Long press on a group tab: change the group type and tuning options.
     *
     * Manually defined groups are persisted directly in the manual config;
     * subscription-provided groups are recorded as a per-profile dynamic
     * override applied by the Go merger at load time. Either way the
     * configuration is reloaded right away.
     */
    private suspend fun overrideGroupType(
        names: List<String>,
        design: ProxyDesign,
        index: Int,
    ) {
        val name = names.getOrNull(index) ?: return
        val group = withClash { queryProxyGroup(name, uiStore.proxySort) }

        val manual = withClash { queryManualConfig() }
        val manualGroup = manual.proxyGroups.find { it.name == name }

        val profileId = withClash { queryActiveProfileId() }
        val overrides = if (profileId.isEmpty() || manualGroup != null) {
            emptyMap()
        } else {
            withClash { queryGroupOverrides(profileId) }.mapValues { (_, fields) ->
                ProxyGroupOverride(
                    type = fields["type"] ?: "select",
                    options = fields - "type",
                )
            }
        }

        val dialog = GroupOverrideFormDialog(
            this,
            name,
            group.type.lowercase(),
            overrides[name],
            isManualGroup = manualGroup != null,
        )

        val (result, restore) = suspendCoroutine { c ->
            dialog.show { override, isRestore ->
                c.resume((override ?: ProxyGroupOverride()) to isRestore)
            }
        }

        if (restore) {
            withClash {
                if (manualGroup != null) {
                    // restore means reset to select with no options for
                    // manual groups; the subscription default has no
                    // meaning there
                    val patched = manualGroup.copy(type = "select", options = emptyMap())
                    patchManualConfig(
                        manual.copy(
                            proxyGroups = manual.proxyGroups.map {
                                if (it.name == name) patched else it
                            }
                        )
                    )
                } else if (profileId.isNotEmpty()) {
                    patchGroupOverrides(
                        profileId,
                        (overrides - name).mapValues { (_, override) ->
                            buildMap {
                                put("type", override.type)
                                putAll(override.options)
                            }
                        }
                    )
                }
            }

            design.requestRedrawVisible()

            design.requests.send(ProxyDesign.Request.Reload(index))
            return
        }

        withClash {
            if (manualGroup != null) {
                val patched = manualGroup.copy(
                    type = result.type,
                    options = result.options,
                )

                patchManualConfig(
                    manual.copy(
                        proxyGroups = manual.proxyGroups.map {
                            if (it.name == name) patched else it
                        }
                    )
                )
            } else if (profileId.isNotEmpty()) {
                patchGroupOverrides(
                    profileId,
                    (overrides + (name to result)).mapValues { (_, override) ->
                        buildMap {
                            put("type", override.type)
                            putAll(override.options)
                        }
                    }
                )
            }
        }

        design.requestRedrawVisible()

        design.requests.send(ProxyDesign.Request.Reload(index))
    }

    /**
     * Long press on a subscription proxy card: edit its dialer-proxy
     * override (per-profile, applied by the Go merger at load time).
     *
     * Groups are skipped (dialer-proxy is not supported on groups) and
     * manually defined proxies are skipped (their dialer-proxy lives in
     * the manual proxy form).
     */
    private suspend fun overrideProxyDialer(design: ProxyDesign, name: String) {
        val manual = withClash { queryManualConfig() }

        if (manual.proxies.any { it.name == name } || manual.proxyGroups.any { it.name == name }) {
            return
        }

        val profileId = withClash { queryActiveProfileId() }
        if (profileId.isEmpty()) {
            return
        }

        val config = withClash {
            val all = queryAllProxies()
            val overrides = queryProxyOverrides(profileId)

            Triple(all, overrides, queryManualConfig())
        }

        val (allProxies, overrides, manualConfig) = config

        val target = allProxies.find { it.name == name } ?: return
        if (target.isGroup) {
            return
        }

        // candidates: any known proxy/group except the target itself and
        // built-in policies; chained through them the traffic reaches the
        // target. Manual entries first (dedup, case-insensitive).
        val candidates = buildList {
            val seen = mutableSetOf(name.lowercase())

            manualConfig.proxyGroups.forEach {
                if (seen.add(it.name.lowercase())) {
                    add(AllProxies(name = it.name, type = it.type, isGroup = true))
                }
            }
            manualConfig.proxies.forEach {
                if (seen.add(it.name.lowercase())) {
                    add(AllProxies(name = it.name, type = it.type))
                }
            }
            allProxies.forEach {
                if (seen.add(it.name.lowercase())) {
                    add(it)
                }
            }
        }.filter {
            !it.isGroup || it.type.lowercase() !in BUILTIN_POLICIES
        }

        val current = overrides[name]?.get("dialer-proxy")

        val dialogResult = suspendCoroutine<Pair<String?, Boolean>> { c ->
            ProxyDialerOverrideDialog(this, name, current, candidates).show { result, isRestore ->
                c.resume(result to isRestore)
            }
        }

        val value = dialogResult.first
        val restore = dialogResult.second

        // negative button / back dismisses with a null result: cancel
        // without touching anything. An empty string from the positive
        // button is the legitimate clear action.
        if (value == null && !restore) {
            return
        }

        try {
            withClash {
                val next = if (restore) {
                    overrides - name
                } else {
                    val trimmed = value?.trim().orEmpty()

                    if (trimmed.isEmpty()) {
                        // empty value removes the override entirely
                        overrides - name
                    } else {
                        overrides + (name to mapOf("dialer-proxy" to trimmed))
                    }
                }

                patchProxyOverrides(profileId, next)
            }
        } catch (e: RuntimeException) {
            design.showExceptionToast(e)
            return
        }

        design.showToast(R.string.override_applied_toast_short, ToastDuration.Short)
        design.requestRedrawVisible()
    }

    companion object {
        private val BUILTIN_POLICIES = setOf("direct", "reject", "reject-drop", "pass", "global")
    }
}