package com.github.kr328.clash.design

import com.github.kr328.clash.core.model.ManualConfig
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.core.model.ManualProxyGroup

/**
 * Shared helpers for the manual config design classes: per-list validation
 * and payload rebuilding for [ManualConfig].
 */
object ManualConfigValidator {
    fun duplicateName(config: ManualConfig, name: String, excludeIndex: Int? = null): Boolean {
        val lower = name.lowercase()

        config.proxies.forEachIndexed { i, p ->
            if (i != excludeIndex && p.name.lowercase() == lower) return true
        }
        config.proxyGroups.forEachIndexed { i, g ->
            if (i != excludeIndex && g.name.lowercase() == lower) return true
        }

        return false
    }

    fun groupInUse(config: ManualConfig, groupName: String): Boolean {
        return config.rules.any { it.target == groupName }
    }

    fun proxyInUse(config: ManualConfig, proxyName: String): Boolean {
        return config.proxyGroups.any { g -> g.proxies.contains(proxyName) }
    }

    fun validateProxy(
        config: ManualConfig,
        current: ManualProxy?,
        name: String,
        server: String,
        port: Int,
        password: String,
    ): Int? {
        if (name.isBlank()) return R.string.manual_error_name_required
        if (server.isBlank()) return R.string.manual_error_server_required
        if (port <= 0 || port > 65535) return R.string.manual_error_port_invalid
        if (password.isEmpty()) return R.string.manual_error_password_required
        if (duplicateName(config, name, current?.let { config.proxies.indexOf(it) })) {
            return R.string.manual_error_duplicate_name
        }

        return null
    }

    fun validateGroupName(
        config: ManualConfig,
        current: ManualProxyGroup?,
        name: String,
    ): Int? {
        if (name.isBlank()) return R.string.manual_error_name_required
        if (duplicateName(config, name, current?.let { config.proxyGroups.indexOf(it) })) {
            return R.string.manual_error_duplicate_name
        }

        return null
    }

    fun validateRule(
        rule: String,
    ): Int? {
        if (rule.isBlank()) return R.string.manual_error_rule_required

        return null
    }
}
