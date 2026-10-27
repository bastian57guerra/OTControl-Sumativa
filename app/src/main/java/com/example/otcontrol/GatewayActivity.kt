package com.example.otcontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.example.otcontrol.databinding.ActivityGatewayBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Teléfono 1: Nodo Gateway OT (junto a la máquina).
 *  - Servidor Bluetooth
 *  - Simula temperatura y distancia (en el proyecto final los entrega el Arduino).
 *  - Aplica la lógica de seguridad OT: la parada de emergencia tiene prioridad absoluta,
 *    se activa sola con un objeto a < 1 m y ante pérdida de enlace (estado seguro).
 */
@SuppressLint("MissingPermission") // permisos verificados en RoleActivity / startServer()
class GatewayActivity : AppCompatActivity() {

    private lateinit var b: ActivityGatewayBinding
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var link: SecureLink? = null
    private var serverEnabled = false

    // Estado de la máquina
    private var stopped = false
    private var stopReason = ""
    private var limitRpm = Protocol.DEFAULT_RPM
    private var cooling = false
    private var tempC = 75f
    private var distM = 2.4f
    private val logLines = ArrayDeque<String>()
    private var lastSave = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startServer() else toast("Permisos de Bluetooth denegados")
        }
    private val enableBt =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) startServer() else toast("Bluetooth desactivado")
        }
    private val discoverable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        b = ActivityGatewayBinding.inflate(layoutInflater)
        setContentView(b.root)
        adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        b.sliderTemp.addOnChangeListener { _, v, _ ->
            tempC = v
            b.tvTempVal.text = "${v.roundToInt()} °C"
        }
        b.sliderDist.addOnChangeListener { _, v, _ ->
            distM = v / 100f
            b.tvDistVal.text = String.format(Locale.getDefault(), "%.2f m", distM)
            safetyCheck()
            pushTelemetry() // un cambio brusco se informa de inmediato
        }

        b.btnServer.setOnClickListener { if (serverEnabled) stopServer() else startServer() }
        b.btnDiscoverable.setOnClickListener {
            if (!BtPermissions.hasAll(this)) {
                permLauncher.launch(BtPermissions.required())
            } else {
                discoverable.launch(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                )
            }
        }
        b.btnRearm.setOnClickListener { rearm() }
        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        log("Nodo Gateway listo. Inicia el servidor para recibir al operador.")
        updateUi()

        // Ciclo de simulación: evalúa seguridad
        lifecycleScope.launch {
            while (isActive) {
                safetyCheck()
                updateUi()
                pushTelemetry()
                saveReading()
                delay(Protocol.TELEMETRY_MS)
            }
        }
    }

    // ---------------------------------------------------------------- servidor BT

    private fun startServer() {
        val ad = adapter
        if (ad == null) {
            toast("Este dispositivo no tiene Bluetooth")
            return
        }
        if (!BtPermissions.hasAll(this)) {
            permLauncher.launch(BtPermissions.required())
            return
        }
        if (!ad.isEnabled) {
            enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (serverEnabled) return
        serverEnabled = true
        b.btnServer.text = "Detener servidor"
        b.tvLink.text = "Esperando operador…"
        ioScope.launch { acceptLoop(ad) }
    }

    private fun stopServer() {
        serverEnabled = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        link?.close("Servidor detenido")
        b.btnServer.text = "Iniciar servidor"
        b.tvLink.text = "Servidor detenido"
        log("Servidor detenido")
    }

    private suspend fun acceptLoop(ad: BluetoothAdapter) {
        while (serverEnabled && ioScope.isActive) {
            try {
                val server = ad.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
                serverSocket = server
                val socket = server.accept() // bloquea hasta que llegue el operador
                server.close() // acceso exclusivo: un solo operador a la vez
                serverSocket = null

                val device = socket.remoteDevice
                val finished = CompletableDeferred<Unit>()
                val l = SecureLink(
                    socket, FrameCipher(AuthManager.linkKey), ioScope,
                    onMessage = { parts -> runOnUiThread { handleCommand(parts) } },
                    onClosed = { reason ->
                        runOnUiThread { onLinkClosed(reason) }
                        finished.complete(Unit)
                    }
                )
                link = l
                runOnUiThread {
                    b.tvLink.text = "Operador conectado: ${device.name ?: device.address}"
                    log("Operador conectado (${device.address})")
                }
                l.start()
                pushTelemetryFromAnyThread()
                finished.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (serverEnabled) {
                    runOnUiThread { log("Error del servidor: ${e.message}") }
                    delay(1000)
                }
            }
        }
    }

    private fun onLinkClosed(reason: String) {
        if (isFinishing || isDestroyed) return
        link = null
        log("Enlace cerrado: $reason")
        b.tvLink.text = if (serverEnabled) "Esperando operador…" else "Servidor detenido"
        // Estado seguro OT: sin operador supervisando, la máquina se detiene.
        if (!stopped) emergencyStop("ENLACE_PERDIDO")
    }

    // ---------------------------------------------------------------- comandos

    private fun handleCommand(parts: List<String>) {
        when (parts.firstOrNull()) {
            Protocol.CMD_ESTOP -> emergencyStop("OPERADOR")
            Protocol.CMD_LIMIT -> parts.getOrNull(1)?.toIntOrNull()?.let {
                limitRpm = it.coerceIn(0, Protocol.MAX_RPM)
                log("Límite RPM = $limitRpm")
            }
            Protocol.CMD_COOL -> {
                cooling = parts.getOrNull(1) == "1"
                log("Enfriamiento ${if (cooling) "ON" else "OFF"}")
            }
            Protocol.CMD_REDUCE -> {
                limitRpm = Protocol.REDUCED_RPM
                log("Reducir velocidad a ${Protocol.REDUCED_RPM} RPM")
            }
        }
        pushTelemetry()
        updateUi()
    }

    private fun emergencyStop(reason: String) {
        if (stopped) return
        stopped = true
        stopReason = reason
        log("PARADA DE EMERGENCIA ($reason)")
        pushTelemetry()
        updateUi()
    }

    private fun rearm() {
        if (!stopped) return
        if (distM < Protocol.ZONE_DANGER_M) {
            toast("No se puede rearmar: hay un objeto a menos de 1 m")
            return
        }
        stopped = false
        stopReason = ""
        log("Motor rearmado localmente")
        pushTelemetry()
        updateUi()
    }

    /** Corte autónomo: no espera intervención humana. */
    private fun safetyCheck() {
        if (!stopped && distM < Protocol.ZONE_DANGER_M) emergencyStop("PELIGRO_PROXIMIDAD")
    }

    // ---------------------------------------------------------------- telemetría / UI

    private fun currentTelemetry() = Telemetry(
        tempC = tempC,
        rpm = if (stopped) 0 else limitRpm,
        distM = distM,
        cooling = cooling,
        limitRpm = limitRpm,
        stopped = stopped,
        stopReason = stopReason
    )

    private fun pushTelemetry() {
        val l = link
        if (l != null && l.isOpen) l.send(currentTelemetry().toFields())
    }

    private fun pushTelemetryFromAnyThread() = runOnUiThread { pushTelemetry() }

    private fun updateUi() {
        val zone = Zone.fromDistance(distM)
        val (zt, zc) = when (zone) {
            Zone.SAFE -> "Zona: SEGURA" to R.color.ok
            Zone.CAUTION -> "Zona: PRECAUCIÓN" to R.color.warn
            Zone.DANGER -> "Zona: PELIGRO" to R.color.danger
        }
        b.tvZoneGw.text = zt
        ViewCompat.setBackgroundTintList(
            b.tvZoneGw, ColorStateList.valueOf(ContextCompat.getColor(this, zc))
        )
        b.tvCoolGw.text = "Enfriamiento: ${if (cooling) "ON" else "OFF"}"

        if (stopped) {
            b.tvMotor.text = "MOTOR: DETENIDO"
            b.tvMotorDetail.text = "Motivo: $stopReason · 0 RPM"
            b.cardMotor.setCardBackgroundColor(ContextCompat.getColor(this, R.color.danger_bg))
            b.cardMotor.strokeColor = ContextCompat.getColor(this, R.color.danger)
        } else {
            b.tvMotor.text = "MOTOR: EN MARCHA"
            b.tvMotorDetail.text = "$limitRpm RPM"
            b.cardMotor.setCardBackgroundColor(ContextCompat.getColor(this, R.color.ok_bg))
            b.cardMotor.strokeColor = ContextCompat.getColor(this, R.color.ok)
        }
        b.btnRearm.isEnabled = stopped
    }

    /** Guarda una lectura en SQLite cada 2 segundos. */
    private fun saveReading() {
        val now = System.currentTimeMillis()
        if (now - lastSave < 2000) return
        lastSave = now
        val t = currentTelemetry()
        ioScope.launch { Db.instance.saveReading(t) }
    }

    private fun log(msg: String) {
        val type = if (msg.startsWith("PARADA")) "PARADA" else "EVENTO"
        ioScope.launch { Db.instance.saveEvent(type, msg) }
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logLines.addFirst("$t  $msg")
        while (logLines.size > 40) logLines.removeLast()
        b.tvLog.text = logLines.joinToString("\n")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        serverEnabled = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        link?.close("Gateway cerrado")
        ioScope.cancel()
    }
}
