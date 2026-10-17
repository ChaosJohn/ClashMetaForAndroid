package com.github.kr328.clash.design.dialog

import android.content.Context
import android.widget.ArrayAdapter
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogManualProxyFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined Shadowsocks proxy.
 *
 * Returns the resulting [ManualProxy] through [show], or null when cancelled.
 */
class ManualProxyFormDialog(
    private val context: Context,
    private val ciphers: List<String>,
    private val initial: ManualProxy?,
    private val existingNames: Set<String>,
) {
    private val initialName = initial?.name.orEmpty()

    private val binding = DialogManualProxyFormBinding
        .inflate(context.layoutInflater, null, false)

    private var name: String = initial?.name.orEmpty()
    private var server: String = initial?.server.orEmpty()
    private var port: Int = initial?.port ?: 0
    private var password: String = initial?.password.orEmpty()
    private var cipher: String = initial?.cipher ?: "aes-256-gcm"
    private var udp: Boolean? = initial?.udp

    private fun validate(): Boolean {
        var valid = true

        binding.nameLayout.error = null
        binding.serverLayout.error = null
        binding.portLayout.error = null
        binding.passwordLayout.error = null

        name = binding.nameField.text?.toString()?.trim().orEmpty()
        server = binding.serverField.text?.toString()?.trim().orEmpty()
        port = binding.portField.text?.toString()?.trim()?.toIntOrNull() ?: 0
        password = binding.passwordField.text?.toString() ?: ""

        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.manual_error_name_required)
            valid = false
        } else if (existingNames.contains(name.lowercase()) && name != initialName) {
            binding.nameLayout.error = context.getString(R.string.manual_error_duplicate_name)
            valid = false
        }

        if (server.isEmpty()) {
            binding.serverLayout.error = context.getString(R.string.manual_error_server_required)
            valid = false
        }

        if (port !in 1..65535) {
            binding.portLayout.error = context.getString(R.string.manual_error_port_invalid)
            valid = false
        }

        if (password.isEmpty()) {
            binding.passwordLayout.error = context.getString(R.string.manual_error_password_required)
            valid = false
        }

        cipher = binding.cipherField.text?.toString()?.trim().orEmpty().ifEmpty { "aes-256-gcm" }
        udp = binding.udpSwitch.isChecked

        return valid
    }

    private fun build(): ManualProxy {
        return (initial ?: ManualProxy()).apply {
            this.name = this@ManualProxyFormDialog.name
            this.server = this@ManualProxyFormDialog.server
            this.port = this@ManualProxyFormDialog.port
            this.password = this@ManualProxyFormDialog.password
            this.cipher = this@ManualProxyFormDialog.cipher
            this.udp = this@ManualProxyFormDialog.udp
        }
    }

    fun show(onResult: (ManualProxy?) -> Unit) {
        var result: ManualProxy? = null

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(
                if (initial == null) R.string.manual_new_proxy else R.string.manual_edit_proxy
            )
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .create()

        dialog.setOnShowListener {
            binding.cipherField.setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    ciphers
                )
            )

            initial?.let { proxy ->
                binding.nameField.setText(proxy.name)
                binding.serverField.setText(proxy.server)
                binding.portField.setText(
                    if (proxy.port > 0) proxy.port.toString() else ""
                )
                binding.passwordField.setText(proxy.password)
                binding.cipherField.setText(proxy.cipher, false)
                binding.udpSwitch.isChecked = proxy.udp == true
            } ?: run {
                binding.cipherField.setText("aes-256-gcm", false)
                binding.udpSwitch.isChecked = true
            }

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
