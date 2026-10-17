package com.github.kr328.clash.design.dialog

import android.content.Context
import android.widget.ArrayAdapter
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.core.model.ManualRule
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogManualRuleFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined rule. Target can be
 * DIRECT, REJECT or one of the manual groups. Returns the result through
 * [show], null when cancelled.
 */
class ManualRuleFormDialog(
    private val context: Context,
    private val groups: List<ManualProxyGroup>,
    private val initial: ManualRule?,
) {
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
        val rule = binding.ruleField.text?.toString()?.trim().orEmpty()
        val group = binding.groupField.text?.toString()?.trim().orEmpty()

        val target = when {
            binding.targetReject.isChecked -> "REJECT"
            group.isNotEmpty() -> group
            else -> "DIRECT"
        }

        return ManualRule(rule = rule, target = target)
    }

    fun show(onResult: (ManualRule?) -> Unit) {
        var result: ManualRule? = null

        val groupNames = groups.map { it.name }

        binding.groupField.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, groupNames)
        )

        initial?.let { rule ->
            binding.ruleField.setText(rule.rule)

            when {
                rule.target == "DIRECT" -> binding.targetDirect.isChecked = true
                rule.target == "REJECT" -> binding.targetReject.isChecked = true
                else -> {
                    binding.targetDirect.isChecked = false
                    binding.targetReject.isChecked = false
                    binding.groupField.setText(rule.target, false)
                }
            }
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
