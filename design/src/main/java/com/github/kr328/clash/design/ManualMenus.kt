package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.github.kr328.clash.design.dialog.AppBottomSheetDialog
import com.github.kr328.clash.design.databinding.DialogManualItemMenuBinding
import com.github.kr328.clash.design.util.layoutInflater

/**
 * Shared "edit / delete" bottom sheet menu for manual config items.
 */
internal fun showManualItemMenu(
    context: Context,
    title: String,
    subtitle: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dialog = AppBottomSheetDialog(context)

    val binding = DialogManualItemMenuBinding
        .inflate(context.layoutInflater, dialog.window?.decorView as ViewGroup?, false)

    binding.titleView.text = title
    binding.subtitleView.setText(subtitle)
    binding.editView.setOnClickListener {
        dialog.dismiss()
        onEdit()
    }
    binding.deleteView.setOnClickListener {
        dialog.dismiss()
        onDelete()
    }

    dialog.setContentView(binding.root)
    dialog.show()
}
