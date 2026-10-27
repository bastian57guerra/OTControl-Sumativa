package com.example.otcontrol

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Aviso sonoro + vibración en el teléfono del operador.

 */
class AlertNotifier(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    } catch (e: RuntimeException) {
        null
    }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var dangerOn = false
    private var lastZone: Zone? = null

    private val dangerLoop = object : Runnable {
        override fun run() {
            pulse(600)
            handler.postDelayed(this, 800)
        }
    }

    fun update(zone: Zone, muted: Boolean) {
        when (zone) {
            Zone.DANGER -> if (!dangerOn) {
                dangerOn = true
                handler.post(dangerLoop)
            }
            Zone.CAUTION -> {
                stopLoop()
                if (lastZone != Zone.CAUTION && !muted) pulse(350)
            }
            Zone.SAFE -> stopLoop()
        }
        lastZone = zone
    }

    fun stop() {
        stopLoop()
        lastZone = null
    }

    fun release() {
        stop()
        tone?.release()
        tone = null
    }

    private fun stopLoop() {
        dangerOn = false
        handler.removeCallbacks(dangerLoop)
        vibrator?.cancel()
    }

    private fun pulse(ms: Long) {
        tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, ms.toInt())
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
