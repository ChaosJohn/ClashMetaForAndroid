package com.github.kr328.clash

import com.github.kr328.clash.core.model.ManualRule
import com.github.kr328.clash.design.ManualRulesDesign
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.dialog.ManualRuleFormDialog
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.util.withClash
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class ManualRulesActivity : BaseActivity<ManualRulesDesign>() {
    override suspend fun main() {
        val design = ManualRulesDesign(this)

        setContentDesign(design)

        withClash {
            design.patchRules(queryManualConfig().rules)
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    if (it == Event.ActivityStart) {
                        withClash {
                            design.patchRules(queryManualConfig().rules)
                        }
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ManualRulesDesign.Request.Create ->
                            editRule(design, null, -1)

                        is ManualRulesDesign.Request.Edit ->
                            editRule(design, it.rule, it.index)

                        is ManualRulesDesign.Request.Delete ->
                            deleteRule(design, it.index)

                        is ManualRulesDesign.Request.MoveUp ->
                            moveRule(design, it.index, it.index - 1)

                        is ManualRulesDesign.Request.MoveDown ->
                            moveRule(design, it.index, it.index + 1)
                    }
                }
            }
        }
    }

    private suspend fun loadConfig(): com.github.kr328.clash.core.model.ManualConfig {
        return withClash { queryManualConfig() }
    }

    private suspend fun saveConfig(
        design: ManualRulesDesign,
        transform: (MutableList<ManualRule>) -> Unit,
    ) {
        try {
            withClash {
                val current = queryManualConfig()
                val rules = current.rules.toMutableList()

                transform(rules)

                patchManualConfig(current.copy(rules = rules))
            }
        } catch (e: Exception) {
            design.showExceptionToast(e)
        }

        design.patchRules(loadConfig().rules)
    }

    private suspend fun editRule(design: ManualRulesDesign, initial: ManualRule?, index: Int) {
        val groups = loadConfig().proxyGroups

        val result = suspendCoroutine<ManualRule?> { continuation ->
            ManualRuleFormDialog(this, groups, initial).show {
                continuation.resume(it)
            }
        } ?: return

        saveConfig(design) { rules ->
            if (initial == null || index < 0 || index >= rules.size) {
                rules.add(result)
            } else {
                rules[index] = result
            }
        }
    }

    private suspend fun deleteRule(design: ManualRulesDesign, index: Int) {
        saveConfig(design) { rules ->
            if (index in rules.indices) {
                rules.removeAt(index)
            }
        }
    }

    private suspend fun moveRule(design: ManualRulesDesign, from: Int, to: Int) {
        saveConfig(design) { rules ->
            if (from in rules.indices && to in rules.indices) {
                val rule = rules.removeAt(from)

                rules.add(to, rule)
            }
        }
    }
}
