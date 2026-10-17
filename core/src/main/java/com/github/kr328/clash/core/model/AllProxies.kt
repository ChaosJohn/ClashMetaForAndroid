package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.Serializable

/**
 * A proxy or group known to the running tunnel, usable as a manual proxy
 * group member.
 */
@Serializable
data class AllProxies(
    val name: String,
    val title: String = "",
    val subtitle: String = "",
    val type: String = "",
    val delay: Int = 0,
    val isGroup: Boolean = false,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<AllProxies> {
        override fun createFromParcel(parcel: Parcel): AllProxies {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<AllProxies?> = arrayOfNulls(size)
    }
}
