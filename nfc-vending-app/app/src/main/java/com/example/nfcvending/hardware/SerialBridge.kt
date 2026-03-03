package com.example.nfcvending.hardware

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SerialBridge(
    private val dataPort: String = "/dev/ttyS4",
    private val debugPort: String = "/dev/ttyS3",
    private val baudRate: Int = 115200
) {
    private var dataOut: FileOutputStream? = null
    private var dataIn: FileInputStream? = null
    private var debugOut: FileOutputStream? = null
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()

    fun open(onTextFrame: (String) -> Unit) {
        val dataFile = File(dataPort)

        try {
            val nativePort = SzzkSerialPort(dataFile, baudRate)
            dataIn = nativePort.inputStream() as FileInputStream
            dataOut = nativePort.outputStream() as FileOutputStream
        } catch (_: Throwable) {
            // Fallback for devices where native lib is missing but raw open still works.
            dataIn = FileInputStream(dataFile)
            dataOut = FileOutputStream(dataFile)
        }

        try {
            debugOut = FileOutputStream(File(debugPort))
        } catch (_: Throwable) {
            debugOut = null
        }

        running.set(true)
        executor.execute {
            val buffer = ByteArray(2048)
            val sb = StringBuilder()
            while (running.get()) {
                val read = try {
                    dataIn?.read(buffer) ?: -1
                } catch (_: Throwable) {
                    -1
                }
                if (read <= 0) continue
                val chunk = String(buffer, 0, read)
                sb.append(chunk)

                var idx = sb.indexOf("\n")
                while (idx >= 0) {
                    val line = sb.substring(0, idx).trim()
                    if (line.isNotEmpty()) onTextFrame(line)
                    sb.delete(0, idx + 1)
                    idx = sb.indexOf("\n")
                }
            }
        }
    }

    fun write(frame: ByteArray) {
        dataOut?.write(frame)
        dataOut?.flush()
        debugOut?.write((frame.joinToString(" ") { "%02X".format(it) } + "\n").toByteArray())
        debugOut?.flush()
    }

    fun close() {
        running.set(false)
        try { dataIn?.close() } catch (_: Throwable) {}
        try { dataOut?.close() } catch (_: Throwable) {}
        try { debugOut?.close() } catch (_: Throwable) {}
        executor.shutdownNow()
    }
}
