package com.example.nfcvending

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.nfcvending.hardware.SerialBridge
import com.example.nfcvending.hardware.VendingCommandService
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var cardNumberView: TextView
    private lateinit var statusView: TextView
    private lateinit var codeEdit: EditText

    private val serialBridge = SerialBridge(dataPort = "/dev/ttyS4", debugPort = "/dev/ttyS3", baudRate = 115200)
    private val commandService = VendingCommandService(serialBridge)

    private var nfcAdapter: NfcAdapter? = null
    private val codeMap: MutableMap<String, CodeAction> = linkedMapOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cardNumberView = findViewById(R.id.tvCardNumber)
        statusView = findViewById(R.id.tvStatus)
        codeEdit = findViewById(R.id.etCode)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        loadCodeMap()

        serialBridge.open { line ->
            // Reader often returns JSON-like or ASCII lines; keep robust extraction.
            val card = extractCardNumber(line)
            runOnUiThread {
                if (card != null) {
                    cardNumberView.text = card
                }
                statusView.text = "RX: $line"
            }
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
    }

    // Numeric mapping proposal:
    // 1BBLLD => open locker: board=BB, lock=LL, door=D
    // 2BBCCX => vend spiral: board=BB, channel=CC (X reserved)
    private fun onEnterCode(code: String) {
        val mapped = codeMap[code]
        if (mapped != null) {
            when (mapped.type) {
                "locker" -> {
                    commandService.openLocker(
                        boardAddress = mapped.board,
                        lockNo = mapped.lockOrChannel,
                        doorNo = mapped.door,
                        open = true
                    )
                    statusView.text = "Mapped locker cmd sent: board=${mapped.board} lock=${mapped.lockOrChannel} door=${mapped.door}"
                }

                "spiral" -> {
                    val tradeNo = System.currentTimeMillis() % 100_000_000L
                    commandService.vendSpiral(
                        boardAddress = mapped.board,
                        channelNo = mapped.lockOrChannel,
                        tradeNo = tradeNo
                    )
                    statusView.text = "Mapped vend cmd sent: board=${mapped.board} channel=${mapped.lockOrChannel} trade=$tradeNo"
                }
            }
            return
        }

        if (code.length < 6 || code.any { !it.isDigit() }) {
            statusView.text = "Invalid code. Use 6 digits."
            return
        }

        val mode = code[0]
        val board = code.substring(1, 3).toInt()
        val p1 = code.substring(3, 5).toInt()
        val p2 = code.substring(5, 6).toInt()

        when (mode) {
            '1' -> {
                commandService.openLocker(boardAddress = board, lockNo = p1, doorNo = p2, open = true)
                statusView.text = "Locker command sent: board=$board lock=$p1 door=$p2"
            }

            '2' -> {
                val tradeNo = System.currentTimeMillis() % 100_000_000L
                commandService.vendSpiral(boardAddress = board, channelNo = p1, tradeNo = tradeNo)
                statusView.text = "Vend command sent: board=$board channel=$p1 trade=$tradeNo"
            }

            else -> statusView.text = "Unknown mode. Use 1 (locker) or 2 (vend)."
        }
    }

    private fun loadCodeMap() {
        runCatching {
            val raw = assets.open("code_map.json").bufferedReader().use { it.readText() }
            val root = JSONObject(raw)
            val codes = root.getJSONObject("codes")
            val keys = codes.keys()
            while (keys.hasNext()) {
                val code = keys.next()
                val entry = codes.getJSONObject(code)
                val type = entry.optString("type")
                val board = entry.optInt("board", 1)
                val lockOrChannel = entry.optInt("lock", entry.optInt("channel", 1))
                val door = entry.optInt("door", 1)
                if ((type == "locker" || type == "spiral") && code.isNotBlank()) {
                    codeMap[code] = CodeAction(type, board, lockOrChannel, door)
                }
            }
            statusView.text = "Ready (${codeMap.size} mapped codes)"
        }.onFailure {
            statusView.text = "Ready (no code_map.json)"
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        val tag: Tag? = intent?.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        if (tag != null) {
            val uid = tag.id.joinToString("") { "%02X".format(it) }
            cardNumberView.text = uid
            statusView.text = "NFC tag detected"
        }
    }

    private fun extractCardNumber(line: String): String? {
        val empRegex = Regex("\"empno\"\\s*:\\s*\"([^\"]+)\"")
        empRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it }

        val authRegex = Regex("\"authcode\"\\s*:\\s*\"([^\"]+)\"")
        authRegex.find(line)?.groupValues?.getOrNull(1)?.let { return it }

        val hexRegex = Regex("\\b[0-9A-Fa-f]{8,16}\\b")
        return hexRegex.find(line)?.value
    }

    data class CodeAction(
        val type: String,
        val board: Int,
        val lockOrChannel: Int,
        val door: Int
    )
}
