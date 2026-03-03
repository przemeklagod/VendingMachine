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

    data class ParsedFrame(
        val sof: Int,
        val version: Int,
        val boardAddress: Int,
        val cmd: Int,
        val payload: ByteArray,
        val crc: Int,
        val crcValid: Boolean,
        val raw: ByteArray
    )

    fun open(
        onTextFrame: (String) -> Unit = {},
        onBinaryFrame: (ParsedFrame) -> Unit = {}
    ): Boolean {
        val dataFile = File(dataPort)
        val opened = runCatching {
            val nativePort = SzzkSerialPort(dataFile, baudRate)
            dataIn = nativePort.inputStream() as FileInputStream
            dataOut = nativePort.outputStream() as FileOutputStream
            true
        }.getOrElse {
            // Fallback for devices where native lib is missing but raw open still works.
            runCatching {
                dataIn = FileInputStream(dataFile)
                dataOut = FileOutputStream(dataFile)
                true
            }.getOrDefault(false)
        }

        if (!opened) {
            dataIn = null
            dataOut = null
            return false
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
            val frameBuffer = ArrayList<Byte>(4096)
            while (running.get()) {
                val read = try {
                    dataIn?.read(buffer) ?: -1
                } catch (_: Throwable) {
                    -1
                }
                if (read <= 0) continue

                // Raw debug mirror.
                runCatching {
                    debugOut?.write(buffer, 0, read)
                    debugOut?.write('\n'.code)
                    debugOut?.flush()
                }

                // Parse binary frames from controller.
                for (i in 0 until read) {
                    frameBuffer.add(buffer[i])
                }
                consumeFrames(frameBuffer, onBinaryFrame)

                // Keep legacy text parsing for JSON/text based readers.
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
        return true
    }

    private fun consumeFrames(buffer: MutableList<Byte>, onBinaryFrame: (ParsedFrame) -> Unit) {
        while (buffer.size >= 11) {
            val sofIndex = buffer.indexOfFirst { b ->
                val v = b.toInt() and 0xFF
                v == 0xEE || v == 0xFF
            }
            if (sofIndex < 0) {
                buffer.clear()
                return
            }
            if (sofIndex > 0) {
                repeat(sofIndex) { buffer.removeAt(0) }
            }
            if (buffer.size < 11) return

            val payloadLen = ((buffer[7].toInt() and 0xFF) shl 8) or (buffer[8].toInt() and 0xFF)
            val frameLen = 9 + payloadLen + 2
            if (payloadLen < 0 || frameLen > 4096) {
                buffer.removeAt(0)
                continue
            }
            if (buffer.size < frameLen) return

            val frame = ByteArray(frameLen)
            for (i in 0 until frameLen) {
                frame[i] = buffer.removeAt(0)
            }
            val parsed = parseFrame(frame) ?: continue
            onBinaryFrame(parsed)
        }
    }

    private fun parseFrame(frame: ByteArray): ParsedFrame? {
        if (frame.size < 11) return null
        val payloadLen = ((frame[7].toInt() and 0xFF) shl 8) or (frame[8].toInt() and 0xFF)
        if (frame.size != 9 + payloadLen + 2) return null
        val payload = frame.copyOfRange(9, 9 + payloadLen)
        val crc = (frame[frame.size - 2].toInt() and 0xFF) or ((frame[frame.size - 1].toInt() and 0xFF) shl 8)

        val calculated = crc16Modbus(frame, frame.size - 2)
        val crcValid = calculated == crc

        val boardAddress =
            ((frame[2].toInt() and 0xFF) shl 24) or
                ((frame[3].toInt() and 0xFF) shl 16) or
                ((frame[4].toInt() and 0xFF) shl 8) or
                (frame[5].toInt() and 0xFF)

        return ParsedFrame(
            sof = frame[0].toInt() and 0xFF,
            version = frame[1].toInt() and 0xFF,
            boardAddress = boardAddress,
            cmd = frame[6].toInt() and 0xFF,
            payload = payload,
            crc = crc,
            crcValid = crcValid,
            raw = frame
        )
    }

    private fun crc16Modbus(data: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
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
