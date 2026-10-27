package com.example.otcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.otcontrol.databinding.ActivityHistoryBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Historial: consulta de lecturas y eventos guardados en SQLite. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var b: ActivityHistoryBinding
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) load() }
        b.toggle.check(R.id.btnReadings)
        b.btnBack.setOnClickListener { finish() }
        b.btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Borrar historial")
                .setMessage("Se borrarán todas las lecturas y eventos guardados.")
                .setPositiveButton("Borrar") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { Db.instance.clearHistory() }
                        load()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) load()
    }

    private fun load() {
        val showReadings = b.toggle.checkedButtonId != R.id.btnEvents
        lifecycleScope.launch {
            val (stats, rows) = withContext(Dispatchers.IO) {
                val db = Db.instance
                val lines = if (showReadings) {
                    db.lastReadings(200).map {
                        "${fmt.format(Date(it.ts))}  ${it.temp.toInt()}°C  ${it.rpm} RPM  " +
                            String.format(Locale.US, "%.2f m", it.dist) + "  ${it.zone}" +
                            (if (it.stopped) "  [DETENIDO]" else "")
                    }
                } else {
                    db.lastEvents(200).map { "${fmt.format(Date(it.ts))}  [${it.type}] ${it.detail}" }
                }
                db.stats() to lines
            }
            b.tvStats.text = "Lecturas: ${stats.count}\n" +
                "Temperatura máxima: ${stats.maxTemp.toInt()} °C\n" +
                String.format(Locale.US, "Distancia mínima: %.2f m\n", stats.minDist) +
                "Paradas registradas: ${stats.stops}"
            b.tvEmpty.visibility = if (rows.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            b.list.adapter = ArrayAdapter(this@HistoryActivity, R.layout.item_row, rows)
        }
    }
}
