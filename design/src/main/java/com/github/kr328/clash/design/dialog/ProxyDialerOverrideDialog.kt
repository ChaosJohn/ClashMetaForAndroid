package com.github.kr328.clash.design.dialog

import android.content.Context
import android.widget.ArrayAdapter
import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogProxyDialerOverrideBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.showSuggestionsOnTap
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form to edit the per-profile dialer-proxy override of a
 * subscription-provided proxy. An empty value (or the clear entry)
 * removes the override; a name selects the proxy or group the traffic
 * is forwarded through first.
 */
class ProxyDialerOverrideDialog(
    private val context: Context,
    private val proxyName: String,
    private val current: String?,
    private val candidates: List<AllProxies>,
) {
    private val binding = DialogProxyDialerOverrideBinding
        .inflate(context.layoutInflater, null, false)

    fun show(onResult: (String?, restore: Boolean) -> Unit) {
        var result: String? = null
        var restore = false

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.proxy_dialer_override_title, proxyName))
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setNeutralButton(R.string.override_restore_default) { _, _ ->
                restore = true
            }
            .create()

        dialog.setOnShowListener {
            binding.dialerProxyField.setText(current.orEmpty(), false)

            if (candidates.isNotEmpty()) {
                binding.dialerProxyField.setAdapter(
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_dropdown_item_1line,
                        listOf(context.getString(R.string.manual_clear_value)) +
                            candidates.map { it.name }
                    )
                )
                binding.dialerProxyField.setOnItemClickListener { _, _, position, _ ->
                    // first entry is the clear action
                    if (position == 0) {
                        binding.dialerProxyField.setText("", false)
                    }
                }
                binding.dialerProxyField.showSuggestionsOnTap()
            }

            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                result = binding.dialerProxyField.text?.toString()?.trim().orEmpty()
                dialog.dismiss()
            }
        }

        dialog.setOnDismissListener {
            onResult(result, restore)
        }

        dialog.show()
    }
}
