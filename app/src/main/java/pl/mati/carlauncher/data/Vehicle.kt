package pl.mati.carlauncher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Dane auta dla całego interfejsu: z adaptera OBD2 (jeśli jest) uzupełnione danymi
 * z dekodera CAN radia (przypisanymi w skanerze „Dane z radia”).
 */
object Vehicle {
    val data = MutableStateFlow(ObdManager.ObdData())
    private var started = false

    fun start() {
        if (started) return
        started = true
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(ObdManager.data, CanRepository.values) { o, c -> merge(o, c) }.collect { data.value = it }
        }
    }

    private fun merge(o: ObdManager.ObdData, c: Map<CanRepository.Field, Double>): ObdManager.ObdData {
        fun i(f: CanRepository.Field) = c[f]?.roundToInt()
        return ObdManager.ObdData(
            speed = o.speed ?: i(CanRepository.Field.SPEED),
            rpm = o.rpm ?: i(CanRepository.Field.RPM),
            coolant = o.coolant ?: i(CanRepository.Field.COOLANT),
            load = o.load ?: i(CanRepository.Field.LOAD),
            mapKpa = o.mapKpa ?: i(CanRepository.Field.MAP),
            baroKpa = o.baroKpa,
            voltage = o.voltage ?: c[CanRepository.Field.VOLTAGE]?.toFloat(),
            outside = i(CanRepository.Field.OUTSIDE),
            fuelPct = i(CanRepository.Field.FUEL),
            rangeKm = i(CanRepository.Field.RANGE),
            odoKm = i(CanRepository.Field.ODO),
        )
    }
}
