package com.example.nfcvending

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.nfcvending.hardware.SerialBridge.ParsedFrame
import com.example.nfcvending.hardware.SerialBridge
import com.example.nfcvending.hardware.VendingCommandService
import java.util.Locale
import kotlin.random.Random

class MainActivity : AppCompatActivity() {
    private lateinit var cardNumberView: TextView
    private lateinit var statusView: TextView
    private lateinit var codeEdit: EditText
    private lateinit var modeLockerButton: Button
    private lateinit var modeSpiralButton: Button
    private lateinit var diagFwButton: Button
    private lateinit var diagStatusButton: Button
    private lateinit var diagProbeButton: Button
    private lateinit var telemetryFwView: TextView
    private lateinit var telemetry47View: TextView
    private lateinit var telemetry44View: TextView
    private lateinit var telemetry4aView: TextView

    private val serialBridge = SerialBridge(dataPort = "/dev/ttyS4", debugPort = "/dev/ttyS3", baudRate = 115200)
    private val commandService = VendingCommandService(serialBridge)

    private var nfcAdapter: NfcAdapter? = null
    private var currentMode: DispenseMode = DispenseMode.LOCKER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardNumberView = findViewById(R.id.tvCardNumber)
        statusView = findViewById(R.id.tvStatus)
        codeEdit = findViewById(R.id.etCode)
        modeLockerButton = findViewById(R.id.btnModeLocker)
        modeSpiralButton = findViewById(R.id.btnModeSpiral)
        diagFwButton = findViewById(R.id.btnDiagFw)
        diagStatusButton = findViewById(R.id.btnDiagStatus)
        diagProbeButton = findViewById(R.id.btnDiagProbe)
        telemetryFwView = findViewById(R.id.tvTelemetryFw)
        telemetry47View = findViewById(R.id.tvTelemetryStatus47)
        telemetry44View = findViewById(R.id.tvTelemetrySensor44)
        telemetry4aView = findViewById(R.id.tvTelemetryTelemetry4a)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        updateModeButtons()

        val serialReady = serialBridge.open(onTextFrame = { line ->
            // Keep text fallback for readers producing text/JSON lines.
            val card = extractCardNumberFromText(line)
            runOnUiThread {
                if (card != null) {
                    cardNumberView.text = card
                }
                statusView.text = "RX(text): $line"
            }
        }, onBinaryFrame = { frame ->
            handleControllerFrame(frame)
        })
        if (!serialReady) {
            statusView.text = "Serial unavailable on this device (UI demo mode)"
        }

        setupKeypad()
        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        serialBridge.close()
    }

    private fun setupKeypad() {
        val digitIds = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )

        digitIds.forEach { id ->
            findViewById<Button>(id).setOnClickListener {
                val digit = (it as Button).text.toString()
                codeEdit.append(digit)
            }
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            codeEdit.setText("")
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener {
            val t = codeEdit.text?.toString().orEmpty()
            if (t.isNotEmpty()) codeEdit.setText(t.dropLast(1))
            codeEdit.setSelection(codeEdit.text?.length ?: 0)
        }

        findViewById<Button>(R.id.btnEnter).setOnClickListener {
            onEnterCode(codeEdit.text?.toString().orEmpty())
        }

        modeLockerButton.setOnClickListener {
            currentMode = DispenseMode.LOCKER
            updateModeButtons()
        }

        modeSpiralButton.setOnClickListener {
            currentMode = DispenseMode.SPIRAL
            updateModeButtons()
        }

        diagFwButton.setOnClickListener {
            val boardId = currentBoardId()
            commandService.queryFirmware(boardId)
            statusView.text = "Sent FW query (cmd=0x01) to board=$boardId"
        }

        diagStatusButton.setOnClickListener {
            val boardId = currentBoardId()
            commandService.queryBoardStatus(boardId)
            statusView.text = "Sent status query (cmd=0x47) to board=$boardId"
        }

        diagProbeButton.setOnClickListener {
            val boardId = currentBoardId()
            commandService.runProbe(boardId)
            statusView.text = "Sent probe sequence to board=$boardId"
        }
    }

    private fun onEnterCode(code: String) {
        if (code.isBlank() || code.any { !it.isDigit() }) {
            statusView.text = "Invalid code. Use numeric selection code."
            return
        }

        val selectionCode = code.toIntOrNull()
        if (selectionCode == null || selectionCode < 0 || selectionCode > 65535) {
            statusView.text = "Selection out of range (0..65535)."
            return
        }

        val boardId = if (currentMode == DispenseMode.LOCKER) 3 else 0
        val token = generateTradeToken()
        commandService.vendSpiral(boardAddress = boardId, channelNo = selectionCode, tradeNo = token)
        statusView.text = "Sent ${currentMode.label}: board=$boardId sel=$selectionCode token=$token"
        codeEdit.setText("")
    }

    private fun updateModeButtons() {
        val isLocker = currentMode == DispenseMode.LOCKER
        modeLockerButton.isEnabled = !isLocker
        modeSpiralButton.isEnabled = isLocker
        statusView.text = "Mode: ${currentMode.label} (board ${if (isLocker) 3 else 0})"
    }

    private fun currentBoardId(): Int = if (currentMode == DispenseMode.LOCKER) 3 else 0

    private fun generateTradeToken(): Long {
        val millisTail = System.currentTimeMillis().toString().takeLast(10)
        val randomTail = Random.nextInt(100000, 999999).toString()
        return (millisTail + randomTail).toLong()
    }

    private fun handleNfcIntent(intent: Intent?) {
        val tag: Tag? = intent?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            }
        }
        if (tag != null) {
            val uid = tag.id.joinToString("") { "%02X".format(it) }
            cardNumberView.text = uid
            statusView.text = "NFC tag detected"
        }
    }

    private fun handleControllerFrame(frame: ParsedFrame) {
        val msg = decodeFrameSummary(frame)
        runOnUiThread {
            statusView.text = msg
            updateTelemetryPanel(frame)
            val card = extractCardNumberFromFrame(frame)
            if (card != null) {
                cardNumberView.text = card
            }
        }
    }

    private fun decodeFrameSummary(frame: ParsedFrame): String {
        val board = frame.boardAddress
        val crcTag = if (frame.crcValid) "" else " [crc?]"
        return when (frame.cmd) {
            0x01 -> {
                val ascii = frame.payload.toString(Charsets.US_ASCII)
                    .replace("\u0000", "")
                    .trim()
                val fw = if (ascii.isNotEmpty()) ascii else frame.payload.toHexString()
                "RX FW board=$board: $fw$crcTag"
            }

            0x13 -> {
                val uid = extractCardNumberFromFrame(frame)
                if (uid != null) "RX CARD board=$board uid=$uid$crcTag"
                else "RX CARD board=$board payload=${frame.payload.toHexString()}$crcTag"
            }

            0x28 -> {
                if (frame.payload.size >= 2) {
                    val channel = frame.payload[0].toInt() and 0xFF
                    val status = frame.payload.last().toInt() and 0xFF
                    "RX VEND board=$board channel=$channel status=0x${status.toString(16).uppercase(Locale.US)}$crcTag"
                } else {
                    "RX VEND board=$board payload=${frame.payload.toHexString()}$crcTag"
                }
            }

            0x47 -> "RX STATUS board=$board ${decode47Payload(frame.payload)}$crcTag"
            0x44 -> "RX SENSOR board=$board ${decode44Payload(frame.payload)}$crcTag"
            0x43 -> "RX HEARTBEAT board=$board payload=${frame.payload.toHexString()}$crcTag"
            0x4A -> "RX TELEMETRY board=$board ${decode4APayload(frame.payload)}$crcTag"
            else -> "RX cmd=0x${frame.cmd.toString(16).uppercase(Locale.US)} board=$board payload=${frame.payload.toHexString()}$crcTag"
        }
    }

    // Observed format in legacy logs:
    // 00 00 [u16] [u16] [u16] 00 00
    // Values are shown as raw + scaled hints for easier diagnostics.
    private fun decode47Payload(payload: ByteArray): String {
        if (payload.size < 10) return "payload=${payload.toHexString()}"
        val a = u16be(payload, 2)
        val b = u16be(payload, 4)
        val c = u16be(payload, 6)
        val tail = payload.copyOfRange(8, payload.size).toHexString()
        val aScaled = "%.2f".format(Locale.US, a / 100.0)
        val bScaled = "%.2f".format(Locale.US, b / 100.0)
        return "rawA=$a (~$aScaled) rawB=$b (~$bScaled) rawC=$c tail=$tail"
    }

    // Observed stable sample: 01 01 00 00 00 00 00
    // Expose first two bytes as mode/state and remaining as bit flags.
    private fun decode44Payload(payload: ByteArray): String {
        if (payload.isEmpty()) return "payload="
        val mode = payload.getOrNull(0)?.toUByte()?.toInt() ?: 0
        val state = payload.getOrNull(1)?.toUByte()?.toInt() ?: 0
        val flags = payload.drop(2).toByteArray().toHexString()
        return "mode=$mode state=$state flags=$flags"
    }

    // Observed format for cmd=0x4A responses:
    // [00 00] [status:1B] [reserved:5B] [queryHi queryLo] [reserved:3B]
    private fun decode4APayload(payload: ByteArray): String {
        if (payload.size < 13) return "payload=${payload.toHexString()}"
        val status = payload[2].toInt() and 0xFF
        val query = ((payload[8].toInt() and 0xFF) shl 8) or (payload[9].toInt() and 0xFF)
        val reservedA = payload.copyOfRange(0, 2).toHexString()
        val reservedB = payload.copyOfRange(3, 8).toHexString()
        val reservedC = payload.copyOfRange(10, payload.size).toHexString()
        return "status=0x${status.toString(16).uppercase(Locale.US)} query=0x${query.toString(16).uppercase(Locale.US)} rA=$reservedA rB=$reservedB rC=$reservedC"
    }

    private fun extractCardNumberFromFrame(frame: ParsedFrame): String? {
        if (frame.cmd != 0x13 || frame.payload.size < 4) return null
        return frame.payload.copyOfRange(0, 4).toHexString()
    }

    private fun updateTelemetryPanel(frame: ParsedFrame) {
        val crcTag = if (frame.crcValid) "" else " [crc?]"
        when (frame.cmd) {
            0x01 -> {
                val ascii = frame.payload.toString(Charsets.US_ASCII)
                    .replace("\u0000", "")
                    .trim()
                val fw = if (ascii.isNotEmpty()) ascii else frame.payload.toHexString()
                telemetryFwView.text = "FW: $fw$crcTag"
            }

            0x47 -> telemetry47View.text = "0x47: ${decode47Payload(frame.payload)}$crcTag"
            0x44 -> telemetry44View.text = "0x44: ${decode44Payload(frame.payload)}$crcTag"
            0x4A -> telemetry4aView.text = "0x4A: ${decode4APayload(frame.payload)}$crcTag"
        }
    }

    private fun extractCardNumberFromText(line: String): String? {
        val empRegex = Regex("\"empno\"\\s*:\\s*\"([^\"]+)\"")
        empRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it }

        val authRegex = Regex("\"authcode\"\\s*:\\s*\"([^\"]+)\"")
        authRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it }

        val hexRegex = Regex("\\b[0-9A-Fa-f]{8,16}\\b")
        return hexRegex.find(line)?.value
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }

    private fun u16be(data: ByteArray, offset: Int): Int {
        if (offset + 1 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    enum class DispenseMode(val label: String) {
        LOCKER("Locker"),
        SPIRAL("Spiral")
    }
}
