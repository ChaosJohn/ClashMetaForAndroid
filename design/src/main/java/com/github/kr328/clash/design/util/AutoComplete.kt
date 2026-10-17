package com.github.kr328.clash.design.util

import com.google.android.material.textfield.MaterialAutoCompleteTextView

/**
 * MaterialAutoCompleteTextView only opens its suggestion popup on tap when
 * inputType is none; editable fields otherwise just raise the keyboard and
 * only offer matches while typing. Explicitly show the full suggestion list
 * on tap and focus so free-editable fields still offer quick picks.
 */
fun MaterialAutoCompleteTextView.showSuggestionsOnTap() {
    setOnClickListener { showDropDown() }

    setOnFocusChangeListener { _, hasFocus ->
        if (hasFocus) {
            showDropDown()
        }
    }
}
