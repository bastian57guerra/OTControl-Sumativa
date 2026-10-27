package com.example.otcontrol

import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enlace Bluetooth RFCOMM cifrado entre los dos teléfonos.

 */
class SecureLink(
    private val socket: BluetoothSocket,
    private val cipher: FrameCipher,
    private val scope: CoroutineScope,
    private val onMessage: (List<String>) -> Unit,
    private val onClosed: (String) -> Unit
) {
    private val writeLock = Mutex()
    private var txSeq = 0L
    private var rxSeq = -1L

    @Volatile
    private var lastRx = SystemClock.elapsedRealtime()
    private val closed = AtomicBoolean(false)
    private var readJob: Job? = null
    private var hbJob: Job? = null

    val isOpen: Boolean get() = !closed.get()

    fun start() {
        lastRx = SystemClock.elapsedRealtime()
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
        hbJob = scope.launch(Dispatchers.IO) {
            while (isActive && isOpen) {
                sendNow(arrayOf(Protocol.HB))
                delay(Protocol.HEARTBEAT_MS)
                if (SystemClock.elapsedRealtime() - lastRx > Protocol.LINK_TIMEOUT_MS) {
                    close("Sin latidos del otro extremo (enlace caído)")
                }
            }
        }
    }

    /** Envío asíncrono  */
    fun send(fields: Array<String>) {
        if (!isOpen) return
        scope.launch(Dispatchers.IO) { sendNow(fields) }
    }



    private suspend fun sendNow(fields: Array<String>) {
        if (!isOpen) return
        try {
            writeLock.withLock {
                val plain = (listOf(txSeq++.toString()) + fields).joinToString("|")
                val line = cipher.encrypt(plain) + "\n"
                socket.outputStream.write(line.toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            }
        } catch (e: IOException) {
            close("Error de escritura en el enlace")
        }
    }

    private fun readLoop() {
        try {
            val reader = socket.inputStream.bufferedReader(Charsets.UTF_8)
            while (isOpen) {
                val line = reader.readLine() ?: break
                val plain = cipher.decrypt(line) ?: continue // clave distinta
                val parts = plain.split('|')
                if (parts.size < 2) continue
                val seq = parts[0].toLongOrNull() ?: continue
                if (seq <= rxSeq) continue // repetida
                rxSeq = seq
                lastRx = SystemClock.elapsedRealtime()
                if (parts[1] == Protocol.HB) continue
                onMessage(parts.drop(1))
            }
            close("Conexión cerrada por el otro extremo")
        } catch (e: IOException) {
            close("Enlace perdido")
        }
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        try {
            socket.close()
        } catch (_: IOException) {
        }
        readJob?.cancel()
        hbJob?.cancel()
        onClosed(reason)
    }
}
