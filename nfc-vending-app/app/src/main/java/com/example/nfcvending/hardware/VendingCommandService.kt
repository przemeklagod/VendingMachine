package com.example.nfcvending.hardware

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
}
