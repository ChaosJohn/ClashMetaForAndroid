package com.github.kr328.clash.design.dialog

import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.core.model.AllProxies
import com.github.kr328.clash.core.model.ManualProxyGroup
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.adapter.ManualMemberCheckAdapter
import com.github.kr328.clash.design.databinding.DialogManualGroupFormBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Modal form for adding / editing a manually defined proxy group.
 *
 * Supported types: select, url-test, fallback and load-balance. Members can
 * be manual proxies, subscription proxies or other manual groups, referenced
 * by name; the member order is editable and significant for fallback and
 * load-balance sticky sessions.
 */
class ManualGroupFormDialog(
    private val context: Context,
    candidates: List<AllProxies>,
    private val initial: ManualProxyGroup?,
    private val existingNames: Set<String>,
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

    private val initialName = initial?.name.orEmpty()
    private val candidates: List<AllProxies> = candidates
    private val selected = LinkedHashSet<String>()

    private val binding = DialogManualGroupFormBinding
        .inflate(context.layoutInflater, null, false)

    init {
        selected.addAll(initial?.proxies.orEmpty())
    }

    private var name: String = initial?.name.orEmpty()
    private var type: String = initial?.type?.takeIf { it in TYPES } ?: TYPE_SELECT

    private lateinit var adapter: ManualMemberCheckAdapter

    private fun memberItems(): List<Pair<String, String>> {
        return candidates.map { p ->
            p.name to (if (p.isGroup) context.getString(R.string.manual_member_group) else p.type)
        }
    }

    private fun rebuildAdapter() {
        val ordered = selected.firstOrNull()?.let {
            // keep selected members on top, in member order
            val chosen = candidates.filter { p -> selected.contains(p.name) }
            val rest = candidates.filterNot { p -> selected.contains(p.name) }
            chosen + rest
        } ?: candidates

        val moveEnabled = type == TYPE_FALLBACK || type == TYPE_LOAD_BALANCE ||
            type == TYPE_URL_TEST

        adapter = ManualMemberCheckAdapter(
            context,
            memberItemsOf(ordered),
            selected,
            moveUp = if (moveEnabled) ({ position -> move(position, -1) }) else null,
            moveDown = if (moveEnabled) ({ position -> move(position, +1) }) else null,
        )

        binding.membersList.layoutManager = LinearLayoutManager(context)
        binding.membersList.adapter = adapter
    }

    private var displayList: List<AllProxies> = candidates

    private fun memberItemsOf(list: List<AllProxies>): List<Pair<String, String>> {
        displayList = list
        return list.map { p ->
            p.name to (if (p.isGroup) context.getString(R.string.manual_member_group) else p.type)
        }
    }

    private fun move(position: Int, direction: Int) {
        val names = selected.toList()
        val from = names.indexOf(displayList.getOrNull(position)?.name ?: return)

        if (from < 0) return

        val to = from + direction

        if (to < 0 || to >= names.size) return

        selected.clear()
        selected.addAll(names.toMutableList().apply {
            val item = removeAt(from)
            add(to, item)
        })

        rebuildAdapter()
    }

    private fun applyTypeVisibility() {
        binding.urlLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.intervalLayout.isVisible = type == TYPE_URL_TEST || type == TYPE_FALLBACK
        binding.strategyLayout.isVisible = type == TYPE_LOAD_BALANCE
        binding.toleranceLayout.isVisible = type == TYPE_URL_TEST
    }

    private fun validate(): Boolean {
        var valid = true

        binding.nameLayout.error = null
        binding.errorView.visibility = View.GONE

        name = binding.nameField.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            binding.nameLayout.error = context.getString(R.string.manual_error_name_required)
            valid = false
        } else if (existingNames.contains(name.lowercase()) &&
            !name.equals(initialName, ignoreCase = true)
        ) {
            binding.nameLayout.error = context.getString(R.string.manual_error_duplicate_name)
            valid = false
        }

        if (selected.isEmpty()) {
            binding.errorView.visibility = View.VISIBLE
            valid = false
        }

        return valid
    }

    private fun build(): ManualProxyGroup {
        val options = mutableMapOf<String, String>()

        when (type) {
            TYPE_URL_TEST, TYPE_FALLBACK -> {
                binding.urlField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["url"] = stripSuggestionDecoration(it, it)
                }
                binding.intervalField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["interval"] = stripSuggestionDecoration(it, it)
                }
            }
        }

        when (type) {
            TYPE_LOAD_BALANCE -> {
                binding.strategyField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    // strip the localized decoration, keep raw strategy
                    options["strategy"] = it.substringBefore('（').substringBefore(" (").trim()
                }
            }
            TYPE_URL_TEST -> {
                binding.toleranceField.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    options["tolerance"] = stripSuggestionDecoration(it, it)
                }
            }
        }

        // never mutate the initial instance: it is still referenced by the
        // list adapter and by the rename lookup in the activity
        return (initial?.copy() ?: ManualProxyGroup()).apply {
            this.name = this@ManualGroupFormDialog.name
            this.type = this@ManualGroupFormDialog.type
            this.proxies = selected.toList()
            this.options = options
        }
    }

    fun show(onResult: (ManualProxyGroup?) -> Unit) {
        var result: ManualProxyGroup? = null

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

        // quick picks: tapping a suggestion fills the editable field
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

        initial?.options?.forEach { (key, value) ->
            when (key) {
                "url" -> binding.urlField.setText(displayTestUrl(value), false)
                "interval" -> binding.intervalField.setText(displayInterval(value), false)
                "tolerance" -> binding.toleranceField.setText(displayTolerance(value), false)
                "strategy" -> binding.strategyField.setText(displayStrategy(value), false)
            }
        }

        applyTypeVisibility()
        rebuildAdapter()

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

    // suggestions show a localized recommendation marker, the raw value is
    // restored on selection and on build()
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

    private fun stripSuggestionDecoration(text: String, raw: String): String {
        // allow the decorated suggestion text itself to map back to raw
        return when (text) {
            context.getString(R.string.manual_suggest_url_gstatic) -> TEST_URLS[0]
            context.getString(R.string.manual_suggest_interval_300) -> "300"
            context.getString(R.string.manual_suggest_tolerance_50) -> "50"
            else -> raw
        }
    }
}
