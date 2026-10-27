package com.example.otcontrol

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado simétrico de cada trama (confidencialidad + integridad).
 * La clave se deriva de la "clave de enlace" compartida .

 */
class FrameCipher(passphrase: String) {
    private val key = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(passphrase.toCharArray(), SALT, 65_536, 256)
            ).encoded,
        "AES"
    )
    private val random = SecureRandom()

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    /** Devuelve null si la trama fue alterada o la clave no coincide. */
    fun decrypt(b64: String): String? = try {
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        if (raw.size <= IV_LEN) null
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_LEN))
            )
            String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val IV_LEN = 12
        const val TAG_BITS = 128
        val SALT = "OTControl-link-v1".toByteArray(Charsets.UTF_8)
    }
}
