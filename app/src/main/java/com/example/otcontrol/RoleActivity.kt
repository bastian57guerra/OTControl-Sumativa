package com.example.otcontrol

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.otcontrol.databinding.ActivityRoleBinding

/** Selector de rol: la misma APK se instala en ambos teléfonos. */
class RoleActivity : AppCompatActivity() {
    private lateinit var b: ActivityRoleBinding
    private var pending: Class<*>? = null

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) {
                pending?.let { startActivity(Intent(this, it)) }
            } else {
                Toast.makeText(
                    this,
                    "Se requieren los permisos de Bluetooth para operar el sistema",
                    Toast.LENGTH_LONG
                ).show()
            }
            pending = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isLoggedIn) {
            goToLogin()
            return
        }
        b = ActivityRoleBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvWelcome.text = "Hola, ${AuthManager.currentUser}"
        b.etKey.setText(AuthManager.linkKey)

        b.cardOperator.setOnClickListener { launchRole(PanelControlActivity::class.java) }
        b.cardGateway.setOnClickListener { launchRole(GatewayActivity::class.java) }
        b.btnLogout.setOnClickListener {
            AuthManager.logout()
            goToLogin()
        }
    }

    private fun launchRole(target: Class<*>) {
        val key = b.etKey.text?.toString().orEmpty()
        if (key.length < 6) {
            Toast.makeText(this, "La clave de enlace debe tener al menos 6 caracteres", Toast.LENGTH_LONG).show()
            return
        }
        AuthManager.linkKey = key
        if (BtPermissions.hasAll(this)) {
            startActivity(Intent(this, target))
        } else {
            pending = target
            permLauncher.launch(BtPermissions.required())
        }
    }

    private fun goToLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
