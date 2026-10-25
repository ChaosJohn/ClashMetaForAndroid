package com.github.kr328.clash.design.dialog

import android.content.Context
import android.widget.ArrayAdapter
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.core.model.ManualRule
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogManualRuleFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.showSuggestionsOnTap
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined rule, split into a rule
 * type (e.g. DOMAIN-SUFFIX), a match value (e.g. google.com) and a target
 * policy (DIRECT, REJECT, REJECT-DROP, PASS, manual groups or any runtime
 * policy from the active profile). The stored [ManualRule.rule] keeps the
 * full "TYPE,VALUE" payload, split on the first comma.
 */
class ManualRuleFormDialog(
    private val context: Context,
    private val groups: List<ManualProxyGroup>,
    private val policies: List<String>,
    private val initial: ManualRule?,
) {
    companion object {
        private val RULE_TYPES =
            listOf(
                "DOMAIN",
                "DOMAIN-SUFFIX",
                "DOMAIN-KEYWORD",
                "IP-CIDR",
                "IP-CIDR6",
                "GEOIP",
                "GEOSITE",
                "DST-PORT",
                "PROCESS-NAME",
            )

        private val BUILTIN_TARGETS = listOf("DIRECT", "REJECT", "REJECT-DROP", "PASS")
    }

    private val binding = DialogManualRuleFormBinding
        .inflate(context.layoutInflater, null, false)

    private fun validate(): Boolean {
        var valid = true

        binding.ruleLayout.error = null

        if (binding.ruleField.text?.toString()?.trim().isNullOrEmpty()) {
            binding.ruleLayout.error = context.getString(R.string.manual_error_rule_required)
            valid = false
        }

        return valid
    }

    private fun build(): ManualRule {
        val type = binding.typeField.text?.toString()?.trim().orEmpty()
        val value = binding.ruleField.text?.toString()?.trim().orEmpty()
        val target = binding.targetField.text?.toString()?.trim().orEmpty()

        val rule =
            if (type.isEmpty()) value else "$type,$value"

        return ManualRule(rule = rule, target = target)
    }

    fun show(onResult: (ManualRule?) -> Unit) {
        var result: ManualRule? = null

        val targetCandidates =
            buildList {
                addAll(BUILTIN_TARGETS)
                addAll(groups.map { it.name })
                addAll(policies)
            }.distinctBy { it.lowercase() }

        binding.typeField.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, RULE_TYPES)
        )
        binding.typeField.showSuggestionsOnTap()

        binding.targetField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_dropdown_item_1line,
                targetCandidates,
            )
        )
        binding.targetField.showSuggestionsOnTap()

        initial?.let { rule ->
            val comma = rule.rule.indexOf(',')
            val type = if (comma >= 0) rule.rule.substring(0, comma) else ""
            val value = if (comma >= 0) rule.rule.substring(comma + 1) else rule.rule

            binding.typeField.setText(type)
            binding.ruleField.setText(value)
            binding.targetField.setText(rule.target)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (initial == null) R.string.manual_new_rule else R.string.manual_edit_rule
            )
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validate()) {
                    result = build()
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnDismissListener {
            onResult(result)
        }

        dialog.show()
    }
}
