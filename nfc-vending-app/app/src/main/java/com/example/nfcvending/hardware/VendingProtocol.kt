package com.example.nfcvending.hardware

object VendingProtocol {
    private const val START_FLAG: Byte = 0xEE.toByte()
    private const val VERSION: Byte = 0x01

    fun buildFrame(address: Int, cmd: Int, data: ByteArray): ByteArray {
        val payloadLength = data.size
        val headerLen = 9
        val frame = ByteArray(headerLen + payloadLength + 2)

        frame[0] = START_FLAG
        frame[1] = VERSION

        frame[2] = ((address ushr 24) and 0xFF).toByte()
        frame[3] = ((address ushr 16) and 0xFF).toByte()
        frame[4] = ((address ushr 8) and 0xFF).toByte()
        frame[5] = (address and 0xFF).toByte()

        frame[6] = (cmd and 0xFF).toByte()
        frame[7] = ((payloadLength ushr 8) and 0xFF).toByte()
        frame[8] = (payloadLength and 0xFF).toByte()

        System.arraycopy(data, 0, frame, 9, payloadLength)

        val crc = crc16Modbus(frame, 0, headerLen + payloadLength)
        frame[headerLen + payloadLength] = (crc and 0xFF).toByte()
        frame[headerLen + payloadLength + 1] = ((crc ushr 8) and 0xFF).toByte()

        return frame
    }

    // Compatible with classic Modbus CRC16, which matches this protocol in many Weimi frames.
    private fun crc16Modbus(data: ByteArray, offset: Int, len: Int): Int {
        var crc = 0xFFFF
        for (i in offset until offset + len) {
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

    fun buildVendPayload(channel: Int, tradeNo: Long): ByteArray {
        val payload = ByteArray(10)
        payload[0] = ((channel ushr 8) and 0xFF).toByte()
        payload[1] = (channel and 0xFF).toByte()

        val tradeHex = tradeNo.toString().padStart(16, '0')
        for (i in 0 until 8) {
            val part = tradeHex.substring(i * 2, i * 2 + 2)
            payload[2 + i] = part.toInt(16).toByte()
        }
        return payload
    }

    fun buildLockerPayload(lockNo: Int, doorNo: Int, open: Boolean): ByteArray {
        return byteArrayOf(
            (lockNo and 0xFF).toByte(),
            (doorNo and 0xFF).toByte(),
            if (open) 0x01 else 0x00
        )
    }
}
