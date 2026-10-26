package com.github.kr328.clash

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Entry point for cmfa://manual QR payloads (v=1&d=<gzip+base64url>).
 *
 * QR scanners and browsers open this activity, which simply forwards the
 * payload to the profiles screen, where the normal import flow (sanitize
 * preview, replace or merge) takes over. No data is processed here.
 */
class ManualImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        val uri: Uri = intent?.data ?: return finish()

        if (uri.host != "manual" || uri.toString().isBlank()) {
            return finish()
        }

        startActivity(
            Intent(this, ProfilesActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(ProfilesActivity.EXTRA_MANUAL_IMPORT_TEXT, uri.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )

        finish()
    }
}
