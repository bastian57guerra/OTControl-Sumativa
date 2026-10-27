package com.example.otcontrol

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Control de acceso local :
 *  - Credenciales almacenadas solo como hash PBKDF2-HMAC-SHA256 con sal aleatoria.
 *  - Bloqueo temporal tras 3 intentos fallidos.
 *  - La sesión vive solo en memoria: al cerrar la app hay que autenticarse de nuevo.
 */
object AuthManager {
    sealed class Result {
        object Ok : Result()
        object Invalid : Result()
        data class Locked(val seconds: Int) : Result()
    }

    private const val PREFS = "ot_auth"
    private const val MAX_FAILS = 3
    private const val LOCK_MS = 30_000L
    private const val DEFAULT_LINK_KEY = "OTControl-2026"

    private lateinit var prefs: SharedPreferences

    @Volatile
    var currentUser: String? = null
        private set

    val isLoggedIn: Boolean get() = currentUser != null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Usuarios de prueba (solo la primera vez)
        if (Db.instance.userCount() == 0) {
            register("OP-001", "1234")
            register("ADMIN", "admin123")
        }
    }

    var linkKey: String
        get() = prefs.getString("link_key", DEFAULT_LINK_KEY) ?: DEFAULT_LINK_KEY
        set(value) {
            prefs.edit().putString("link_key", value).apply()
        }

    fun login(user: String, pin: String): Result {
        val now = System.currentTimeMillis()
        val lockUntil = prefs.getLong("lock_until", 0L)
        if (now < lockUntil) return Result.Locked(((lockUntil - now) / 1000 + 1).toInt())

        val id = user.trim().uppercase()
        val stored = Db.instance.getUser(id)
        val ok = if (stored != null) {
            val (s, h) = stored
            MessageDigest.isEqual(hash(pin, Base64.decode(s, Base64.NO_WRAP)), Base64.decode(h, Base64.NO_WRAP))
        } else {
            hash(pin, ByteArray(16)) // iguala tiempos para no revelar si el usuario existe
            false
        }

        if (ok) {
            prefs.edit().putInt("fails", 0).apply()
            currentUser = id
            return Result.Ok
        }

        val fails = prefs.getInt("fails", 0) + 1
        return if (fails >= MAX_FAILS) {
            prefs.edit().putInt("fails", 0).putLong("lock_until", now + LOCK_MS).apply()
            Result.Locked((LOCK_MS / 1000).toInt())
        } else {
            prefs.edit().putInt("fails", fails).apply()
            Result.Invalid
        }
    }

    fun logout() {
        currentUser = null
    }

    private fun register(user: String, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Db.instance.addUser(
            user.uppercase(),
            Base64.encodeToString(salt, Base64.NO_WRAP),
            Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP)
        )
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256))
            .encoded
}
