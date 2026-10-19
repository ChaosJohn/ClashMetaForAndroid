package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.core.model.ManualProxy
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogManualProxyFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.showSuggestionsOnTap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Modal form for adding / editing a manually defined proxy.
 *
 * Supported types: ss (cipher + password) and vless (uuid + transport
 * options). Fields not covered by the common form are carried through
 * [ManualProxy.extras] verbatim, so adding further protocol types only
 * needs form fields, not model changes.
 */
class ManualProxyFormDialog(
    private val context: Context,
    private val ciphers: List<String>,
    private val initial: ManualProxy?,
    private val existingNames: Set<String>,
    private val dialerCandidates: List<AllProxies> = emptyList(),
) {
    companion object {
        private const val TYPE_SS = "ss"
        private const val TYPE_VLESS = "vless"

        private val TYPES = listOf(TYPE_SS, TYPE_VLESS)

        private val FINGERPRINTS = listOf("chrome", "firefox", "safari", "ios", "android", "edge", "random")
        private val NETWORKS = listOf("tcp", "ws")
    }

    private val initialName = initial?.name.orEmpty()

    private val binding = DialogManualProxyFormBinding
        .inflate(context.layoutInflater, null, false)

    private var name: String = initial?.name.orEmpty()
    private var server: String = initial?.server.orEmpty()
    private var port: Int = initial?.port ?: 0
    private var password: String = initial?.password.orEmpty()
    private var cipher: String = initial?.cipher ?: "aes-256-gcm"
    private var udp: Boolean? = initial?.udp
    private var type: String = initial?.type?.takeIf { it in TYPES } ?: TYPE_SS
    private var extras: Map<String, String> = initial?.extras ?: emptyMap()

    private val fromExtras: Map<String, String> by lazy {
        val wsOpts = extras["ws-opts"]?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
        }
        val wsHeaders = wsOpts?.get("headers")?.let {
            runCatching { it.jsonObject["Host"]?.jsonPrimitive?.content }.getOrNull()
        }

        buildMap {
            extras["uuid"]?.let { put("uuid", it) }
            if (extras["tls"] == "true") {
                put("tls", "true")
            }
            extras["network"]?.let { put("network", it) }
            extras["servername"]?.let { put("servername", it) }
            extras["client-fingerprint"]?.let { put("client-fingerprint", it) }
            wsOpts?.get("path")?.let { put("ws-path", it.jsonPrimitive.content) }
            wsHeaders?.let { put("ws-host", it) }
            extras["dialer-proxy"]?.let { put("dialer-proxy", it) }
        }.filterValues { it.isNotEmpty() }
    }

    private fun validate(): Boolean {
        var valid = true

        binding.nameLayout.error = null
        binding.serverLayout.error = null
        binding.portLayout.error = null
        binding.passwordLayout.error = null
        binding.uuidLayout.error = null

        name = binding.nameField.text?.toString()?.trim().orEmpty()
        server = binding.serverField.text?.toString()?.trim().orEmpty()
        port = binding.portField.text?.toString()?.trim()?.toIntOrNull() ?: 0
        password = binding.passwordField.text?.toString() ?: ""

        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.manual_error_name_required)
            valid = false
        } else if (existingNames.contains(name.lowercase()) &&
            !name.equals(initialName, ignoreCase = true)
        ) {
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

        if (type == TYPE_SS && password.isEmpty()) {
            binding.passwordLayout.error = context.getString(R.string.manual_error_password_required)
            valid = false
        }

        if (type == TYPE_VLESS && binding.uuidField.text.isNullOrBlank()) {
            binding.uuidLayout.error = context.getString(R.string.manual_error_uuid_required)
            valid = false
        }

        cipher = binding.cipherField.text?.toString()?.trim().orEmpty().ifEmpty { "aes-256-gcm" }
        udp = binding.udpSwitch.isChecked

        return valid
    }

    private fun build(): ManualProxy {
        // extras values are raw JSON text so the mapping stays
        // Parcel-friendly: plain strings unquoted, objects as JSON objects
        val builtExtras = if (type == TYPE_VLESS) {
            buildMap {
                binding.uuidField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("uuid", it)
                }
                if (binding.tlsSwitch.isChecked) {
                    put("tls", "true")
                }
                binding.networkField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("network", it)
                }
                binding.servernameField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("servername", it)
                }
                binding.fingerprintField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("client-fingerprint", it)
                }

                val wsPath = binding.wsPathField.text?.toString()?.trim().orEmpty()
                val wsHost = binding.wsHostField.text?.toString()?.trim().orEmpty()

                if (wsPath.isNotEmpty() || wsHost.isNotEmpty()) {
                    put(
                        "ws-opts",
                        buildJsonObject {
                            if (wsPath.isNotEmpty()) {
                                put("path", wsPath)
                            }
                            if (wsHost.isNotEmpty()) {
                                put("headers", buildJsonObject {
                                    put("Host", wsHost)
                                })
                            }
                        }.toString()
                    )
                }

                binding.dialerProxyField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("dialer-proxy", it)
                }
            }
        } else {
            binding.dialerProxyField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                mapOf("dialer-proxy" to it)
            } ?: emptyMap()
        }

        // never mutate the initial instance: it is still referenced by the
        // list adapter and by the rename lookup in the activity
        return (initial?.copy() ?: ManualProxy()).apply {
            this.name = this@ManualProxyFormDialog.name
            this.type = this@ManualProxyFormDialog.type
            this.server = this@ManualProxyFormDialog.server
            this.port = this@ManualProxyFormDialog.port
            this.password = this@ManualProxyFormDialog.password
            this.cipher = this@ManualProxyFormDialog.cipher
            this.udp = this@ManualProxyFormDialog.udp

            this.extras = builtExtras
        }
    }

    private fun applyTypeVisibility() {
        val vless = type == TYPE_VLESS

        binding.passwordLayout.isVisible = !vless
        binding.cipherLayout.isVisible = !vless
        binding.uuidLayout.isVisible = vless
        binding.tlsSwitch.isVisible = vless
        binding.networkLayout.isVisible = vless

        binding.servernameLayout.isVisible = vless && binding.tlsSwitch.isChecked
        binding.fingerprintLayout.isVisible = vless && binding.tlsSwitch.isChecked
        binding.wsPathLayout.isVisible = vless && binding.networkField.text?.toString() == "ws"
        binding.wsHostLayout.isVisible = vless && binding.networkField.text?.toString() == "ws"
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
            binding.typeField.setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_list_item_1,
                    TYPES
                )
            )
            binding.typeField.setText(type, false)
            binding.typeField.setOnItemClickListener { _, _, position, _ ->
                type = TYPES[position]
                applyTypeVisibility()
            }

            binding.cipherField.setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    ciphers
                )
            )

            binding.networkField.setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_list_item_1,
                    NETWORKS
                )
            )
            binding.networkField.setOnItemClickListener { _, _, _, _ ->
                applyTypeVisibility()
            }

            binding.fingerprintField.setAdapter(
                ArrayAdapter(
                    context,
                    android.R.layout.simple_dropdown_item_1line,
                    FINGERPRINTS
                )
            )

            if (dialerCandidates.isNotEmpty()) {
                binding.dialerProxyField.setAdapter(
                    ArrayAdapter(
                        context,
                        android.R.layout.simple_dropdown_item_1line,
                        dialerCandidates.map { it.name }
                    )
                )
                binding.dialerProxyField.showSuggestionsOnTap()
            }

            binding.tlsSwitch.setOnCheckedChangeListener { _, _ ->
                applyTypeVisibility()
            }

            initial?.let { proxy ->
                binding.nameField.setText(proxy.name)
                binding.serverField.setText(proxy.server)
                binding.portField.setText(
                    if (proxy.port > 0) proxy.port.toString() else ""
                )
                binding.passwordField.setText(proxy.password)
                binding.cipherField.setText(proxy.cipher, false)
                binding.udpSwitch.isChecked = proxy.udp == true

                if (type == TYPE_VLESS) {
                    binding.uuidField.setText(fromExtras["uuid"])
                    binding.tlsSwitch.isChecked = fromExtras["tls"] == "true"
                    binding.networkField.setText(fromExtras["network"] ?: "tcp", false)
                    binding.servernameField.setText(fromExtras["servername"])
                    binding.fingerprintField.setText(fromExtras["client-fingerprint"], false)
                    binding.wsPathField.setText(fromExtras["ws-path"])
                    binding.wsHostField.setText(fromExtras["ws-host"])
                }

                binding.dialerProxyField.setText(fromExtras["dialer-proxy"], false)
            } ?: run {
                binding.cipherField.setText("aes-256-gcm", false)
                binding.udpSwitch.isChecked = true
            }

            applyTypeVisibility()

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
