package com.github.kr328.clash

import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.ManualProxyGroupsDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.ManualGroupFormDialog
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class ManualProxyGroupsActivity : BaseActivity<ManualProxyGroupsDesign>() {
    override suspend fun main() {
        val design = ManualProxyGroupsDesign(this)

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
                        ManualProxyGroupsDesign.Request.Create ->
                            editGroup(design, null)

                        is ManualProxyGroupsDesign.Request.Edit ->
                            editGroup(design, it.group)

                        is ManualProxyGroupsDesign.Request.Delete ->
                            deleteGroup(design, it.group)
                    }
                }
            }
        }
    }

    private suspend fun reload(design: ManualProxyGroupsDesign) {
        withClash {
            design.patchConfig(queryManualConfig())
        }
    }

    private suspend fun editGroup(design: ManualProxyGroupsDesign, initial: ManualProxyGroup?) {
        val config = withClash {
            val manual = queryManualConfig()
            val all = queryAllProxies()

            manual to all
        }

        val (manualConfig, allProxies) = config

        if (initial == null && manualConfig.proxies.isEmpty() && allProxies.isEmpty()) {
            design.showToast(R.string.manual_error_no_members, ToastDuration.Long)

            return
        }

        // candidates = subscription proxies/groups + manual proxies/groups.
        // When the tunnel is running, queryAllProxies already contains the
        // manual entries, so dedup by name (case-insensitive), manual first.
        val candidates = buildList {
            val seen = mutableSetOf<String>()

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
        }

        val existingNames = buildSet {
            manualConfig.proxies.forEach { add(it.name.lowercase()) }
            manualConfig.proxyGroups.forEach { add(it.name.lowercase()) }
        }

        val result = suspendCoroutine<ManualProxyGroup?> { continuation ->
            ManualGroupFormDialog(
                this,
                candidates,
                initial,
                existingNames,
            ).show {
                continuation.resume(it)
            }
        } ?: return

        withClash {
            val current = queryManualConfig()
            val groups = current.proxyGroups.toMutableList()

            if (initial != null) {
                val index = groups.indexOfFirst { it.name == initial.name }

                if (index >= 0) groups[index] = result else groups.add(result)
            } else {
                groups.add(result)
            }

            // propagate renames into rule targets and other groups' member lists
            val renamed = initial != null && initial.name != result.name

            val rules = if (renamed) {
                current.rules.map { rule ->
                    if (rule.target == initial.name) {
                        rule.copy(target = result.name)
                    } else {
                        rule
                    }
                }
            } else {
                current.rules
            }

            val patchedGroups = if (renamed) {
                groups.map { group ->
                    if (group.name != initial.name && group.proxies.contains(initial.name)) {
                        group.copy(
                            proxies = group.proxies.map { member ->
                                if (member == initial.name) result.name else member
                            }
                        )
                    } else {
                        group
                    }
                }
            } else {
                groups
            }

            try {
                patchManualConfig(current.copy(proxyGroups = patchedGroups, rules = rules))
            } catch (e: Exception) {
                design.showExceptionToast(e)
            }
        }

        reload(design)
    }

    private suspend fun deleteGroup(design: ManualProxyGroupsDesign, group: ManualProxyGroup) {
        val config = withClash { queryManualConfig() }

        val inUse = config.rules.any { it.target == group.name } ||
            config.proxyGroups.any { other -> other.proxies.contains(group.name) }

        if (inUse) {
            design.showToast(R.string.manual_error_group_in_use, ToastDuration.Long)

            return
        }

        withClash {
            patchManualConfig(
                config.copy(
                    proxyGroups = config.proxyGroups.filterNot { it.name == group.name }
                )
            )
        }

        reload(design)
    }
}
