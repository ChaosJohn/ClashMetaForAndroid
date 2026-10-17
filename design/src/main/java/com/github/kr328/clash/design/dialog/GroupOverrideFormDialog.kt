package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import com.github.kr328.clash.core.model.ProxyGroupOverride
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.databinding.DialogGroupOverrideFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form shown on long press of a proxy group tab on the proxies
 * screen. Changes the group type of the selected group and optionally its
 * tuning options.
 *
 * Returns null when the user cancelled or chose to restore the
 * subscription default (the caller distinguishes these cases).
 */
class GroupOverrideFormDialog(
    private val context: Context,
    private val groupName: String,
    private val currentType: String,
    initial: ProxyGroupOverride?,
    private val isManualGroup: Boolean,
) {
    companion object {
        private val TYPE_SELECT = "select"
        private val TYPE_URL_TEST = "url-test"
        private val TYPE_FALLBACK = "fallback"
        private val TYPE_LOAD_BALANCE = "load-balance"

        private val TYPES = listOf(TYPE_SELECT, TYPE_URL_TEST, TYPE_FALLBACK, TYPE_LOAD_BALANCE)

        private val STRATEGIES = listOf(
            "round-robin",
            "consistent-hashing",
            "sticky-sessions",
        )

        private val TEST_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "http://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "http://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.platform.hicloud.com/generate_204",
        )

        private val INTERVALS = listOf("60", "300", "600", "1800", "3600")

        private val TOLERANCES = listOf("0", "50", "100", "150", "300")
    }

    private val binding = DialogGroupOverrideFormBinding
        .inflate(context.layoutInflater, null, false)

    private var type: String = initial?.type?.takeIf { it in TYPES }
        ?: currentType.takeIf { it in TYPES }
        ?: TYPE_SELECT

    /** true when the user picked "restore subscription default" */
    private var restore = false

    init {
        initial?.options?.forEach { (key, value) ->
            when (key) {
                "url" -> binding.urlField.setText(displayTestUrl(value), false)
                "interval" -> binding.intervalField.setText(displayInterval(value), false)
                "tolerance" -> binding.toleranceField.setText(displayTolerance(value), false)
                "strategy" -> binding.strategyField.setText(displayStrategy(value), false)
            }
        }
    }

    fun show(onResult: (ProxyGroupOverride?, Boolean) -> Unit) {
        var result: ProxyGroupOverride? = null

        binding.typeField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                TYPES.map { displayType(it) }
            )
        )
        binding.typeField.setText(displayType(type), false)
        binding.typeField.setOnItemClickListener { _, _, position, _ ->
            type = TYPES[position]
            applyTypeVisibility()
        }

        binding.strategyField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                STRATEGIES.map { displayStrategy(it) }
            )
        )

        binding.urlField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                TEST_URLS.map { displayTestUrl(it) }
            )
        )
        binding.urlField.setOnItemClickListener { _, _, position, _ ->
            binding.urlField.setText(TEST_URLS[position], false)
            binding.urlField.setSelection(TEST_URLS[position].length)
        }

        binding.intervalField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                INTERVALS.map { displayInterval(it) }
            )
        )
        binding.intervalField.setOnItemClickListener { _, _, position, _ ->
            binding.intervalField.setText(INTERVALS[position], false)
        }

        binding.toleranceField.setAdapter(
            ArrayAdapter(
                context,
                android.R.layout.simple_list_item_1,
                TOLERANCES.map { displayTolerance(it) }
            )
        )
        binding.toleranceField.setOnItemClickListener { _, _, position, _ ->
            binding.toleranceField.setText(TOLERANCES[position], false)
        }

        applyTypeVisibility()

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.override_group_title, groupName))
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ -> }
            .setNegativeButton(R.string.cancel) { _, _ -> }

        if (isManualGroup) {
            // a manual group stores the type directly, nothing to restore
            builder.setNeutralButton(null, null)
        } else {
            builder.setNeutralButton(R.string.override_restore_default) { _, _ ->
                restore = true
                onResult(null, true)
            }
        }

        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (validate()) {
                    result = build()
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnDismissListener {
            if (!restore) {
                onResult(result, false)
            }
        }

        dialog.show()
    }

    private fun applyTypeVisibility() {
        binding.urlLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.intervalLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.strategyLayout.isVisible = type == TYPE_LOAD_BALANCE
        binding.toleranceLayout.isVisible = type == TYPE_URL_TEST
    }

    private fun validate(): Boolean {
        var valid = true

        binding.errorView.visibility = View.GONE

        val url = binding.urlField.text?.toString()?.trim().orEmpty()
        val interval = binding.intervalField.text?.toString()?.trim().orEmpty()
        val tolerance = binding.toleranceField.text?.toString()?.trim().orEmpty()

        if (type == TYPE_URL_TEST || type == TYPE_FALLBACK) {
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                valid = false
            }

            if (interval.isNotEmpty() && interval.toIntOrNull()?.let { it > 0 } != true) {
                valid = false
            }
        }

        if (type == TYPE_URL_TEST && tolerance.isNotEmpty()) {
            if (tolerance.toIntOrNull() == null || tolerance.toIntOrNull()!! < 0) {
                valid = false
            }
        }

        if (!valid) {
            binding.errorView.visibility = View.VISIBLE
        }

        return valid
    }

    private fun build(): ProxyGroupOverride {
        val options = mutableMapOf<String, String>()

        when (type) {
            TYPE_URL_TEST, TYPE_FALLBACK -> {
                binding.urlField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["url"] = stripSuggestionDecoration(it)
                }
                binding.intervalField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["interval"] = stripSuggestionDecoration(it)
                }
            }
        }

        when (type) {
            TYPE_LOAD_BALANCE -> {
                binding.strategyField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["strategy"] = it.substringBefore('（').substringBefore(" (").trim()
                }
            }
            TYPE_URL_TEST -> {
                binding.toleranceField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["tolerance"] = stripSuggestionDecoration(it)
                }
            }
        }

        return ProxyGroupOverride(type = type, options = options)
    }

    private fun displayType(type: String): String = when (type) {
        TYPE_SELECT -> context.getString(R.string.manual_group_type_select)
        TYPE_URL_TEST -> context.getString(R.string.manual_group_type_url_test)
        TYPE_FALLBACK -> context.getString(R.string.manual_group_type_fallback)
        TYPE_LOAD_BALANCE -> context.getString(R.string.manual_group_type_load_balance)
        else -> type
    }

    private fun displayStrategy(strategy: String): String = when (strategy) {
        "round-robin" -> context.getString(R.string.manual_group_strategy_round_robin)
        "consistent-hashing" -> context.getString(R.string.manual_group_strategy_consistent_hashing)
        "sticky-sessions" -> context.getString(R.string.manual_group_strategy_sticky_sessions)
        else -> strategy
    }

    private fun displayTestUrl(url: String): String = if (url == TEST_URLS[0])
        context.getString(R.string.manual_suggest_url_gstatic)
    else
        url

    private fun displayInterval(value: String): String = when (value) {
        "300" -> context.getString(R.string.manual_suggest_interval_300)
        else -> value
    }

    private fun displayTolerance(value: String): String = when (value) {
        "50" -> context.getString(R.string.manual_suggest_tolerance_50)
        else -> value
    }

    private fun stripSuggestionDecoration(text: String): String = when (text) {
        context.getString(R.string.manual_suggest_url_gstatic) -> TEST_URLS[0]
        context.getString(R.string.manual_suggest_interval_300) -> "300"
        context.getString(R.string.manual_suggest_tolerance_50) -> "50"
        else -> text
    }
}
