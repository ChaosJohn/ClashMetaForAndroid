package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A dynamic type override for one subscription-provided proxy group.
 * Applied at profile load time by the Go merger; the subscription file
 * itself is never modified.
 *
 * [type] is one of select / url-test / fallback / load-balance. [options]
 * carries the tuning fields of the target type ("url", "interval",
 * "tolerance", "strategy"), values hold raw JSON like ManualProxy.extras.
 */
@Serializable
data class ProxyGroupOverride(
    @SerialName("type")
    var type: String = "select",

    @SerialName("options")
    var options: Map<String, String> = emptyMap(),
)
