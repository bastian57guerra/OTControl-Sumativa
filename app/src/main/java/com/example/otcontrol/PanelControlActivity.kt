package com.example.otcontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.example.otcontrol.databinding.ActivityPanelBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pantallas 2 y 3: Panel de control OT + sensor de proximidad + alerta preventiva.
 * Teléfono del operador (cliente Bluetooth).
 */
@SuppressLint("MissingPermission") // los permisos se verifican antes en RoleActivity
class PanelControlActivity : AppCompatActivity() {

    private lateinit var b: ActivityPanelBinding
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var adapter: BluetoothAdapter? = null
    private var link: SecureLink? = null
    private var socketInProgress: BluetoothSocket? = null
    private lateinit var notifier: AlertNotifier

    private var acknowledged = false
    private var syncing = false
    private var dragging = false
    private var lastZone: Zone? = null
    private var lastSave = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) beginConnect() else toast("Permisos de Bluetooth denegados")
        }
    private val enableBt =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (adapter?.isEnabled == true) beginConnect() else toast("Bluetooth desactivado")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        b = ActivityPanelBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        notifier = AlertNotifier(this)

        b.tvTitle.text = "◄ Panel Operativo  ·  ${AuthManager.currentUser}"
        b.btnConnect.setOnClickListener {
            if (link?.isOpen == true) link?.close("Desconectado por el operador") else beginConnect()
        }

        // Activador 1: sistema de enfriamiento
        b.switchCooling.setOnCheckedChangeListener { _, checked ->
            if (!syncing) sendCommand(Protocol.CMD_COOL, if (checked) "1" else "0")
        }

        // Activador 2: límite de RPM (0 a 3000)
        b.sliderLimit.addOnChangeListener { _, value, _ -> b.tvLimit.text = value.roundToInt().toString() }
        b.sliderLimit.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                sendCommand(Protocol.CMD_LIMIT, slider.value.roundToInt().toString())
            }
        })

        // Acciones de la alerta preventiva
        b.btnReduce.setOnClickListener {
            sendCommand(Protocol.CMD_REDUCE)
            acknowledge()
        }
        b.btnAck.setOnClickListener { acknowledge() }

        // Parada de emergencia: máxima prioridad, un solo toque
        b.btnEstop.setOnClickListener { sendCommand(Protocol.CMD_ESTOP) }
        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }

        showDisconnected()
    }

    // ---------------------------------------------------------------- conexión

    private fun beginConnect() {
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
        val devices = ad.bondedDevices.toList()
        if (devices.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Sin dispositivos emparejados")
                .setMessage("Empareja primero el teléfono Nodo Gateway desde Ajustes > Bluetooth.")
                .setPositiveButton("Abrir ajustes") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val names = devices.map { "${it.name ?: "Dispositivo"}\n${it.address}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Selecciona el Nodo Gateway")
            .setItems(names) { _, i -> connectTo(devices[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun connectTo(device: BluetoothDevice) {
        b.tvConnState.text = "Estado: CONECTANDO…"
        b.btnConnect.isEnabled = false
        ioScope.launch {
            var socket: BluetoothSocket? = null
            try {
                adapter?.cancelDiscovery()
                socket = device.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
                socketInProgress = socket
                socket.connect()
                val cipher = FrameCipher(AuthManager.linkKey)
                val l = SecureLink(
                    socket, cipher, ioScope,
                    onMessage = { parts -> runOnUiThread { handleMessage(parts) } },
                    onClosed = { reason -> runOnUiThread { onLinkClosed(reason) } }
                )
                link = l
                runOnUiThread { showConnected() }
                l.start()
            } catch (e: Exception) {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                runOnUiThread {
                    toast("No se pudo conectar: ${e.message ?: "error desconocido"}")
                    showDisconnected()
                }
            }
        }
    }

    private fun onLinkClosed(reason: String) {
        ioScope.launch { Db.instance.saveEvent("ENLACE", reason) }
        if (isFinishing || isDestroyed) return
        link = null
        notifier.stop()
        showDisconnected()
        toast(reason)
    }

    private fun sendCommand(vararg fields: String) {
        ioScope.launch { Db.instance.saveEvent("COMANDO", fields.joinToString(" ")) }
        val l = link
        if (l == null || !l.isOpen) {
            toast("Sin enlace con el Nodo Gateway")
            return
        }
        l.send(arrayOf(*fields))
    }

    // ---------------------------------------------------------------- recepción

    private fun handleMessage(parts: List<String>) {
        if (parts.firstOrNull() == Protocol.TEL) {
            Telemetry.parse(parts)?.let { render(it) }
        }
    }

    private fun render(t: Telemetry) {
        b.tvTemp.text = "${t.tempC.roundToInt()} °C"
        b.tvRpm.text = t.rpm.toString()
        b.tvDistance.text = String.format(Locale.getDefault(), "%.1f m", t.distM)
        b.proxBar.setDistance(t.distM)

        val zone = Zone.fromDistance(t.distM)

        // Guarda la lectura cada 2 s y registra los cambios de zona
        val now = System.currentTimeMillis()
        if (now - lastSave >= 2000) {
            lastSave = now
            ioScope.launch { Db.instance.saveReading(t) }
        }
        if (zone != lastZone && zone != Zone.SAFE) {
            ioScope.launch { Db.instance.saveEvent("ALERTA", "Zona ${zone.name} a ${t.distM} m") }
        }
        if (zone != lastZone && zone != Zone.CAUTION) acknowledged = false
        lastZone = zone

        val (zoneText, zoneColor) = when (zone) {
            Zone.SAFE -> "Zona: SEGURA" to R.color.ok
            Zone.CAUTION -> "Zona: PRECAUCIÓN" to R.color.warn
            Zone.DANGER -> "Zona: PELIGRO" to R.color.danger
        }
        b.tvZone.text = zoneText
        ViewCompat.setBackgroundTintList(
            b.tvZone, ColorStateList.valueOf(ContextCompat.getColor(this, zoneColor))
        )

        // Banner de alerta (Pantalla 3)
        when {
            t.stopped -> showBanner(
                "⛔ MOTOR DETENIDO",
                "Motivo: ${reasonText(t.stopReason)}",
                R.color.danger_bg, R.color.danger
            )
            zone == Zone.DANGER -> showBanner(
                "🚨 PELIGRO CRÍTICO",
                String.format(Locale.getDefault(), "Objeto a %.1f m · detención automática", t.distM),
                R.color.danger_bg, R.color.danger
            )
            zone == Zone.CAUTION -> showBanner(
                "⚠ ALERTA PREVENTIVA",
                String.format(Locale.getDefault(), "Objeto detectado a %.1f m", t.distM),
                R.color.warn_bg, R.color.warn
            )
            else -> b.cardAlert.visibility = View.GONE
        }
        tintCard(
            b.cardProx,
            when (zone) {
                Zone.SAFE -> R.color.card to R.color.accent
                Zone.CAUTION -> R.color.warn_bg to R.color.warn
                Zone.DANGER -> R.color.danger_bg to R.color.danger
            }
        )

        val showActions = zone == Zone.CAUTION && !t.stopped && !acknowledged
        b.cardSuggest.visibility = if (showActions) View.VISIBLE else View.GONE

        notifier.update(zone, acknowledged)

        // Sincroniza activadores con el estado real de la máquina
        syncing = true
        if (b.switchCooling.isChecked != t.cooling) b.switchCooling.isChecked = t.cooling
        if (!dragging) {
            val v = ((t.limitRpm / 100) * 100).coerceIn(0, Protocol.MAX_RPM).toFloat()
            if (b.sliderLimit.value != v) b.sliderLimit.value = v
            b.tvLimit.text = v.roundToInt().toString()
        }
        syncing = false
    }

    private fun reasonText(code: String) = when (code) {
        "OPERADOR" -> "parada de emergencia del operador"
        "PELIGRO_PROXIMIDAD" -> "objeto a menos de 1 m (automática)"
        "ENLACE_PERDIDO" -> "pérdida de enlace de seguridad"
        "" -> "—"
        else -> code
    }

    private fun showBanner(title: String, detail: String, bg: Int, stroke: Int) {
        b.tvAlertTitle.text = title
        b.tvAlertDetail.text = detail
        tintCard(b.cardAlert, bg to stroke)
        b.cardAlert.visibility = View.VISIBLE
    }

    private fun tintCard(card: com.google.android.material.card.MaterialCardView, colors: Pair<Int, Int>) {
        card.setCardBackgroundColor(ContextCompat.getColor(this, colors.first))
        card.strokeColor = ContextCompat.getColor(this, colors.second)
    }

    private fun acknowledge() {
        acknowledged = true
        b.cardSuggest.visibility = View.GONE
        notifier.update(Zone.CAUTION, true)
    }

    // ---------------------------------------------------------------- estado UI

    private fun showConnected() {
        b.tvConnState.text = "Estado: CONECTADO"
        b.btnConnect.text = "Desconectar"
        b.btnConnect.isEnabled = true
        tintCard(b.cardConn, R.color.ok_bg to R.color.ok)
        setControlsEnabled(true)
    }

    private fun showDisconnected() {
        b.tvConnState.text = "Estado: DESCONECTADO"
        b.btnConnect.text = "Conectar"
        b.btnConnect.isEnabled = true
        tintCard(b.cardConn, R.color.danger_bg to R.color.danger)
        b.cardAlert.visibility = View.GONE
        b.cardSuggest.visibility = View.GONE
        b.tvTemp.text = "-- °C"
        b.tvRpm.text = "--"
        b.tvDistance.text = "-- m"
        b.tvZone.text = "Zona: --"
        ViewCompat.setBackgroundTintList(
            b.tvZone, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.text_dim))
        )
        b.proxBar.setDistance(3f)
        tintCard(b.cardProx, R.color.card to R.color.card_stroke)
        acknowledged = false
        lastZone = null
        setControlsEnabled(false)
    }

    private fun setControlsEnabled(on: Boolean) {
        b.switchCooling.isEnabled = on
        b.sliderLimit.isEnabled = on
        b.btnReduce.isEnabled = on
        b.btnAck.isEnabled = on
        // La parada de emergencia siempre queda visible; sin enlace avisa al pulsarla.
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        if (::notifier.isInitialized) notifier.release()
        link?.close("Panel cerrado")
        try {
            socketInProgress?.close()
        } catch (_: Exception) {
        }
        ioScope.cancel()
    }
}
