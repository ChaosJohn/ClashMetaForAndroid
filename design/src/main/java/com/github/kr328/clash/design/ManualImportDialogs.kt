package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.github.kr328.clash.core.model.ManualImportItem
import com.github.kr328.clash.core.model.ManualImportPreview
import com.github.kr328.clash.design.databinding.DialogManualImportPreviewBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Result of the import preview dialog: the user either dismissed the
 * dialog or confirmed with a semantics (replace / merge).
 */
sealed class ManualImportDecision {
    object Dismissed : ManualImportDecision()
    object Replace : ManualImportDecision()
    object Merge : ManualImportDecision()
}

/**
 * Import preview dialog: entry counts, per-entry status (dropped entries
 * struck out), sanitizer warnings and the credential notice. Resumes with
 * the user's replace / merge decision.
 */
suspend fun Context.showManualImportPreview(
    preview: ManualImportPreview,
): ManualImportDecision {
    val total = preview.proxies.size + preview.proxyGroups.size + preview.rules.size

    if (total == 0) {
        Toast.makeText(this, R.string.manual_import_no_entries, Toast.LENGTH_LONG).show()
        return ManualImportDecision.Dismissed
    }

    return withContext(Dispatchers.Main) {
        val binding = DialogManualImportPreviewBinding.inflate(layoutInflater)

        binding.summaryView.text = getString(
            R.string.manual_import_preview_summary,
            preview.proxies.size,
            preview.proxyGroups.size,
            preview.rules.size,
        )

        binding.proxiesView.bindManualImportItems(preview.proxies)
        binding.groupsView.bindManualImportItems(preview.proxyGroups)
        binding.rulesView.bindManualImportItems(preview.rules)

        if (preview.warnings.isNotEmpty()) {
            binding.warningsView.text = preview.warnings.joinToString(separator = "\n")
            binding.warningsView.visibility = View.VISIBLE
        }

        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(this@showManualImportPreview)
                .setTitle(R.string.manual_import_preview_title)
                .setView(binding.root)
                .setPositiveButton(R.string.manual_import_merge, null)
                .setNegativeButton(R.string.manual_import_replace, null)
                .setNeutralButton(R.string.cancel) { _, _ -> }
                .setOnDismissListener {
                    if (continuation.isActive)
                        continuation.resume(ManualImportDecision.Dismissed)
                }
                .show()

            // override the auto-dismiss handlers so the decision is resumed
            // exactly once before the dialog closes
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                if (continuation.isActive)
                    continuation.resume(ManualImportDecision.Merge)
                dialog.dismiss()
            }
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                if (continuation.isActive)
                    continuation.resume(ManualImportDecision.Replace)
                dialog.dismiss()
            }

            continuation.invokeOnCancellation { dialog.dismiss() }
        }
    }
}

/**
 * Renders one preview list: name plus type, dropped entries dimmed and
 * struck through.
 */
private fun LinearLayout.bindManualImportItems(items: List<ManualImportItem>) {
    val density = resources.displayMetrics.density

    items.forEach { item ->
        val label = TextView(context).apply {
            text = if (item.type.isBlank())
                item.name
            else
                "${item.name}  ·  ${item.type}"

            alpha = if (item.ok) 1.0f else 0.45f

            if (!item.ok)
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG

            setPadding(
                (density * 4).toInt(),
                (density * 2).toInt(),
                (density * 4).toInt(),
                (density * 2).toInt(),
            )
        }

        addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }
}
