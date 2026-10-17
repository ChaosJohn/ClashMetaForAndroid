package com.github.kr328.clash.core.model

import android.os.Parcel
import android.os.Parcelable
import com.github.kr328.clash.core.util.Parcelizer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ManualProxy(
    @SerialName("name")
    var name: String = "",

    @SerialName("type")
    var type: String = "ss",

    @SerialName("server")
    var server: String = "",

    @SerialName("port")
    var port: Int = 0,

    @SerialName("password")
    var password: String = "",

    @SerialName("cipher")
    var cipher: String = "aes-256-gcm",

    @SerialName("udp")
    var udp: Boolean? = null,
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualProxy> {
        override fun createFromParcel(parcel: Parcel): ManualProxy {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualProxy?> = arrayOfNulls(size)
    }
}

@Serializable
data class ManualProxyGroup(
    @SerialName("name")
    var name: String = "",

    @SerialName("type")
    var type: String = "select",

    @SerialName("proxies")
    var proxies: List<String> = emptyList(),

    /**
     * Extra mihomo group options, e.g. "url", "interval", "strategy",
     * "tolerance". Merged verbatim into the generated group mapping.
     */
    @SerialName("options")
    var options: Map<String, String> = emptyMap(),
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualProxyGroup> {
        override fun createFromParcel(parcel: Parcel): ManualProxyGroup {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualProxyGroup?> = arrayOfNulls(size)
    }
}

@Serializable
data class ManualRule(
    @SerialName("rule")
    var rule: String = "",

    @SerialName("target")
    var target: String = "",
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualRule> {
        override fun createFromParcel(parcel: Parcel): ManualRule {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualRule?> = arrayOfNulls(size)
    }
}

/**
 * User-defined proxies / proxy groups / rules merged into every loaded
 * profile. Stored independently of profile content so subscription updates
 * never wipe them.
 */
@Serializable
data class ManualConfig(
    @SerialName("proxies")
    var proxies: List<ManualProxy> = emptyList(),

    @SerialName("proxy-groups")
    var proxyGroups: List<ManualProxyGroup> = emptyList(),

    @SerialName("rules")
    var rules: List<ManualRule> = emptyList(),
) : Parcelable {
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        Parcelizer.encodeToParcel(serializer(), parcel, this)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ManualConfig> {
        override fun createFromParcel(parcel: Parcel): ManualConfig {
            return Parcelizer.decodeFromParcel(serializer(), parcel)
        }

        override fun newArray(size: Int): Array<ManualConfig?> = arrayOfNulls(size)
    }
}
