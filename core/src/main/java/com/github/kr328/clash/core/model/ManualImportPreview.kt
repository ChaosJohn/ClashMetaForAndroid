package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One import entry in the manual config import preview.
 *
 * For proxies and groups [name] is the entry name and [type] its mihomo
 * type; for rules [name] holds the match part and [type] the target.
 * [ok] reports whether the entry survives sanitization.
 */
@Serializable
data class ManualImportItem(
    @SerialName("name")
    val name: String = "",

    @SerialName("type")
    val type: String = "",

    @SerialName("ok")
    val ok: Boolean = true,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualImportItem> {
        override fun createFromParcel(parcel: Parcel): ManualImportItem {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualImportItem?> = arrayOfNulls(size)
    }
}

/**
 * Preview document produced by the Go sanitizer for the import dialog:
 * per-item status, human-readable warnings (dangling references etc.), the
 * number of removed members/entries and the sanitized package ready for
 * persistence via patchManualConfig.
 */
@Serializable
data class ManualImportPreview(
    @SerialName("proxies")
    val proxies: List<ManualImportItem> = emptyList(),

    @SerialName("proxyGroups")
    val proxyGroups: List<ManualImportItem> = emptyList(),

    @SerialName("rules")
    val rules: List<ManualImportItem> = emptyList(),

    @SerialName("warnings")
    val warnings: List<String> = emptyList(),

    @SerialName("droppedCount")
    val droppedCount: Int = 0,

    @SerialName("sanitized")
    val sanitized: ManualConfig? = null,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualImportPreview> {
        override fun createFromParcel(parcel: Parcel): ManualImportPreview {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualImportPreview?> = arrayOfNulls(size)
    }
}
