package pl.mati.carlauncher.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Stan telefonu połączonego z radiem (Bluetooth HFP): operator, zasięg, bateria. */
object PhoneStatusRepository {

    data class Status(
        val operator: String? = null,
        val signal: Int? = null,   // 0..5
        val battery: Int? = null,  // 0..5
        val network: Boolean? = null,
        val updated: Long = 0,
    )

    val status = MutableStateFlow(Status())

    /** Wołane przez nasłuch komunikatów dla android.bluetooth.headsetclient.profile.action.AG_EVENT. */
    fun onAgEvent(extras: Map<String, String>) {
        var s = status.value
        for ((k, v) in extras) {
            when {
                k.endsWith("NETWORK_SIGNAL_STRENGTH") -> s = s.copy(signal = v.toIntOrNull())
                k.endsWith("BATTERY_LEVEL") -> s = s.copy(battery = v.toIntOrNull())
                k.endsWith("NETWORK_STATUS") -> s = s.copy(network = v == "1")
                k.endsWith("OPERATOR_NAME") -> {
                    // np. "0,Play" – bierzemy część po przecinku
                    val name = v.substringAfter(",", v).trim().takeIf { it.isNotBlank() && it != "null" }
                    if (name != null) s = s.copy(operator = name)
                }
            }
        }
        status.value = s.copy(updated = System.currentTimeMillis())
    }

    fun onDisconnected() {
        status.value = Status()
    }
}
