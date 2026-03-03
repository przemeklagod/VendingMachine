package com.example.nfcvending.hardware

import kotlin.concurrent.thread

class VendingCommandService(private val bridge: SerialBridge) {

    // Command 40 from original MotorInstruct.setShipments(...)
    fun vendSpiral(boardAddress: Int, channelNo: Int, tradeNo: Long) {
        val payload = VendingProtocol.buildVendPayload(channelNo, tradeNo)
        val frame = VendingProtocol.buildFrame(boardAddress, 40, payload)
        bridge.write(frame)
    }

    // Command 86 from original MotorInstruct.unlockRFIDDoor(...)
    fun openLocker(boardAddress: Int, lockNo: Int, doorNo: Int, open: Boolean = true) {
        val payload = VendingProtocol.buildLockerPayload(lockNo, doorNo, open)
        val frame = VendingProtocol.buildFrame(boardAddress, 86, payload)
        bridge.write(frame)
    }

    fun sendRaw(boardAddress: Int, cmd: Int, payload: ByteArray = byteArrayOf()) {
        val frame = VendingProtocol.buildFrame(boardAddress, cmd, payload)
        bridge.write(frame)
    }

    // From original logs: EE ... 01 0002 0000
    fun queryFirmware(boardAddress: Int) {
        sendRaw(boardAddress, 0x01, byteArrayOf(0x00, 0x00))
    }

    // From original logs: EE ... 47 0000
    fun queryBoardStatus(boardAddress: Int) {
        sendRaw(boardAddress, 0x47, byteArrayOf())
    }

    // Sends the startup diagnostic sequence observed in Weimi logs.
    fun runProbe(boardAddress: Int) {
        thread(start = true, name = "board-probe") {
            val sequence = listOf(
                0x77 to byteArrayOf(0x00),
                0x78 to byteArrayOf(0x00),
                0x01 to byteArrayOf(0x00, 0x00),
                0x43 to byteArrayOf(0x01, 0x01),
                0x43 to byteArrayOf(0x02, 0x01),
                0x47 to byteArrayOf(),
                0x44 to byteArrayOf()
            )
            sequence.forEach { (cmd, payload) ->
                sendRaw(boardAddress, cmd, payload)
                Thread.sleep(120)
            }
        }
    }
}
