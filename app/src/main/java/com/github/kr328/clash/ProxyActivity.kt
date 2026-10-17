package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroupOverride
import com.github.kr328.clash.design.ProxyDesign
import com.github.kr328.clash.design.dialog.GroupOverrideFormDialog
import com.github.kr328.clash.design.model.ProxyState
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
}