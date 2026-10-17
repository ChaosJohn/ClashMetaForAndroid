package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.ManualMemberCheckAdapter
import com.github.kr328.clash.design.databinding.DialogManualGroupFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined select-type proxy group
 * over manual proxies. Returns the result through [show], null when cancelled.
 */
class ManualGroupFormDialog(
    private val context: Context,
    proxies: List<ManualProxy>,
    private val initial: ManualProxyGroup?,
    private val existingNames: Set<String>,
) {
    private val initialName = initial?.name.orEmpty()
    private val proxies: List<ManualProxy> = proxies
    private val selected = mutableSetOf<String>()

    private val binding = DialogManualGroupFormBinding
        .inflate(context.layoutInflater, null, false)

    init {
        selected.addAll(initial?.proxies.orEmpty())
    }

    private var name: String = initial?.name.orEmpty()

    private fun validate(): Boolean {
        var valid = true

        binding.nameLayout.error = null
        binding.errorView.visibility = View.GONE

        name = binding.nameField.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.manual_error_name_required)
            valid = false
        } else if (existingNames.contains(name.lowercase()) && name != initialName) {
            binding.nameLayout.error = context.getString(R.string.manual_error_duplicate_name)
            valid = false
        }

        if (selected.isEmpty()) {
            binding.errorView.visibility = View.VISIBLE
            valid = false
        }

        return valid
    }

    fun show(onResult: (ManualProxyGroup?) -> Unit) {
        var result: ManualProxyGroup? = null

        val adapter = ManualMemberCheckAdapter(context, proxies, selected)

        binding.membersList.layoutManager = LinearLayoutManager(context)
        binding.membersList.adapter = adapter

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (initial == null) R.string.manual_new_group else R.string.manual_edit_group
            )
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()

        dialog.setOnShowListener {
            initial?.let { group ->
                binding.nameField.setText(group.name)
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validate()) {
                    result = ManualProxyGroup(
                        name = name,
                        type = "select",
                        proxies = proxies.map { it.name }.filter { selected.contains(it) },
                    )
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
