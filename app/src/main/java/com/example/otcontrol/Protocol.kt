package com.example.otcontrol

import java.util.Locale
import java.util.UUID

/** Constantes y tramas del protocolo entre Gateway (Teléfono 1) y Operador HMI (Teléfono 2). */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("8f1d5c3a-2b7e-4a8e-9c61-0d3f7b2a5e11")
    const val SERVICE_NAME = "OTControlGateway"

    const val HEARTBEAT_MS = 2000L      // pulso de conexión cada 2 s
    const val LINK_TIMEOUT_MS = 6000L   // 3 latidos perdidos = enlace caído
    const val TELEMETRY_MS = 500L

    const val MAX_RPM = 3000
    const val DEFAULT_RPM = 1500
    const val REDUCED_RPM = 800

    const val ZONE_SAFE_M = 2.0f
    const val ZONE_DANGER_M = 1.0f

    // Tipos de trama
    const val HB = "HB"
    const val TEL = "TEL"
    const val CMD_ESTOP = "ESTOP"
    const val CMD_LIMIT = "LIMIT"
    const val CMD_COOL = "COOL"
    const val CMD_REDUCE = "REDUCE"
}

enum class Zone {
    SAFE, CAUTION, DANGER;

    companion object {
        fun fromDistance(m: Float): Zone = when {
            m < Protocol.ZONE_DANGER_M -> DANGER
            m <= Protocol.ZONE_SAFE_M -> CAUTION
            else -> SAFE
        }
    }
}

data class Telemetry(
    val tempC: Float,
    val rpm: Int,
    val distM: Float,
    val cooling: Boolean,
    val limitRpm: Int,
    val stopped: Boolean,
    val stopReason: String
) {
    fun toFields(): Array<String> = arrayOf(
        Protocol.TEL,
        String.format(Locale.US, "%.1f", tempC),
        rpm.toString(),
        String.format(Locale.US, "%.2f", distM),
        if (cooling) "1" else "0",
        limitRpm.toString(),
        if (stopped) "1" else "0",
        stopReason
    )

    companion object {
        /** parts[0] debe ser "TEL". Devuelve null si la trama está mal formada. */
        fun parse(parts: List<String>): Telemetry? = try {
            if (parts.size < 8 || parts[0] != Protocol.TEL) null
            else Telemetry(
                tempC = parts[1].toFloat(),
                rpm = parts[2].toInt(),
                distM = parts[3].toFloat(),
                cooling = parts[4] == "1",
                limitRpm = parts[5].toInt(),
                stopped = parts[6] == "1",
                stopReason = parts[7]
            )
        } catch (e: NumberFormatException) {
            null
        }
    }
}
