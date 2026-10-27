package com.example.otcontrol

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import com.example.otcontrol.databinding.ActivityLoginBinding

/** Pantalla 1: Login de seguridad (control de acceso). */
class LoginActivity : AppCompatActivity() {
    private lateinit var b: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnLogin.setOnClickListener { attemptLogin() }
        b.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }
    }

    private fun attemptLogin() {
        val user = b.etUser.text?.toString().orEmpty().trim()
        val pin = b.etPin.text?.toString().orEmpty()

        b.tilUser.error = if (user.isEmpty()) "Ingresa tu ID de operador" else null
        b.tilPin.error = if (pin.isEmpty()) "Ingresa tu contraseña o PIN" else null
        if (user.isEmpty() || pin.isEmpty()) return

        when (val r = AuthManager.login(user, pin)) {
            is AuthManager.Result.Ok -> {
                b.etPin.text?.clear()
                hideError()
                startActivity(Intent(this, RoleActivity::class.java))
                finish()
            }
            is AuthManager.Result.Invalid -> showError("Credenciales incorrectas")
            is AuthManager.Result.Locked ->
                showError("Acceso bloqueado por intentos fallidos. Reintenta en ${r.seconds} s")
        }
    }

    private fun showError(msg: String) {
        b.tvLoginError.text = msg
        b.tvLoginError.visibility = android.view.View.VISIBLE
    }

    private fun hideError() {
        b.tvLoginError.visibility = android.view.View.GONE
    }
}
