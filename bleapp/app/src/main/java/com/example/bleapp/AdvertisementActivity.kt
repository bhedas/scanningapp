package com.example.bleapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdvertisementActivity : AppCompatActivity() {

    private lateinit var address: String
    private lateinit var deviceName: String
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // Views
    private lateinit var deviceNameText: TextView
    private lateinit var deviceAddressText: TextView
    private lateinit var scanStatusText: TextView
    private lateinit var statPackets: TextView
    private lateinit var statMedian: TextView
    private lateinit var statRssi: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var stopButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var connectButton: MaterialButton

    // State
    private var packetCount = 0
    private var lastPacketTimeMs = 0L
    private var isListening = false
    private val allDeltas = mutableListOf<Long>()

    // Plain text log for export (timestamp + MFR hex only)
    private val exportLog = StringBuilder()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ── Colors ────────────────────────────────────────────────────────────────
    companion object {
        // Everything in the log is dim grey — only RSSI changes color
        val CLR_DIVIDER  = Color.parseColor("#1A2327")
        val CLR_LABEL    = Color.parseColor("#37474F")   // dimmer — field names
        val CLR_DIM      = Color.parseColor("#607D8B")   // base dim grey for all values
        val CLR_BRIGHT   = Color.parseColor("#78909C")   // slightly brighter for key values

        fun rssiColor(rssi: Int) = when {
            rssi >= -50 -> Color.parseColor("#69F0AE")
            rssi >= -65 -> Color.parseColor("#B2FF59")
            rssi >= -75 -> Color.parseColor("#FFD740")
            rssi >= -85 -> Color.parseColor("#FF6D00")
            else        -> Color.parseColor("#FF1744")
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advertisement)

        address    = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS) ?: ""
        deviceName = intent.getStringExtra(MainActivity.EXTRA_DEVICE_NAME) ?: "Unknown Device"

        supportActionBar?.title = "Advertisement Analyzer"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        deviceNameText  = findViewById(R.id.deviceNameText)
        deviceAddressText = findViewById(R.id.deviceAddressText)
        scanStatusText  = findViewById(R.id.scanStatusText)
        statPackets     = findViewById(R.id.statPackets)
        statMedian      = findViewById(R.id.statMedian)
        statRssi        = findViewById(R.id.statRssi)
        logView         = findViewById(R.id.advLogView)
        logScroll       = findViewById(R.id.advLogScroll)
        stopButton      = findViewById(R.id.stopButton)
        clearButton     = findViewById(R.id.clearButton)
        saveButton      = findViewById(R.id.saveButton)
        connectButton   = findViewById(R.id.connectButton)

        deviceNameText.text    = deviceName
        deviceAddressText.text = address

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        stopButton.setOnClickListener    { if (isListening) pauseListening() else resumeListening() }
        clearButton.setOnClickListener   { clearLog() }
        saveButton.setOnClickListener    { saveLog() }
        connectButton.setOnClickListener {
            startActivity(Intent(this, DeviceActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, address)
                putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
            })
        }

        startListening()
    }

    override fun onResume()  { super.onResume();  if (!isListening) resumeListening() }
    override fun onPause()   { super.onPause();   pauseListening() }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    // ── Scan Control ──────────────────────────────────────────────────────────

    private fun startListening() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        val filter   = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0).build()
        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            isListening = true
            setStatusUI(true)
        } catch (e: SecurityException) {
            scanStatusText.text = "Permission denied"
            scanStatusText.setTextColor(Color.parseColor("#FF1744"))
        }
    }

    private fun pauseListening() {
        try { bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
        isListening = false
        setStatusUI(false)
    }

    private fun resumeListening() = startListening()

    private fun setStatusUI(on: Boolean) {
        if (on) {
            scanStatusText.text = "LIVE"
            scanStatusText.setTextColor(Color.parseColor("#69F0AE"))
            stopButton.text = "Stop"
            stopButton.setBackgroundColor(Color.parseColor("#B71C1C"))
        } else {
            scanStatusText.text = "PAUSED  —  scroll freely to copy"
            scanStatusText.setTextColor(Color.parseColor("#FFD54F"))
            stopButton.text = "Resume"
            stopButton.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { logAdvertisement(result) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanStatusText.text = "Error ($errorCode)"
                scanStatusText.setTextColor(Color.parseColor("#FF1744"))
            }
        }
    }

    // ── Advertisement Logger ──────────────────────────────────────────────────

    private fun logAdvertisement(result: ScanResult) {
        packetCount++

        val bootMs      = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val packetMs    = bootMs + result.timestampNanos / 1_000_000
        val timeStr     = timeFmt.format(Date(packetMs))
        val deltaMs     = if (lastPacketTimeMs == 0L) null else packetMs - lastPacketTimeMs
        lastPacketTimeMs = packetMs

        if (deltaMs != null) allDeltas.add(deltaMs)
        updateStats(result.rssi, packetCount)

        val record = result.scanRecord
        val sb = SpannableStringBuilder()

        // Divider
        sb.dim("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n", CLR_DIVIDER)

        // Packet # and timestamp on same line
        sb.dim("  #$packetCount", CLR_LABEL)
        sb.dim("   $timeStr", CLR_BRIGHT)
        if (deltaMs != null) sb.dim("   +${deltaMs}ms\n", CLR_DIM)
        else                 sb.dim("   first packet\n", CLR_LABEL)

        // RSSI — only field with changing color
        sb.dim("  RSSI         ", CLR_LABEL)
        sb.colored("${result.rssi} dBm   ${rssiBar(result.rssi)}\n", rssiColor(result.rssi))

        if (record != null) {
            // Local Name
            record.deviceName?.takeIf { it.isNotBlank() }?.let {
                sb.row("  Name         ", it, CLR_BRIGHT)
            }

            // TX Power
            if (record.txPowerLevel != Int.MIN_VALUE) {
                sb.row("  TX Power     ", "${record.txPowerLevel} dBm", CLR_DIM)
            }

            // Flags
            if (record.advertiseFlags != -1) {
                sb.row("  Flags        ",
                    "0x${"%02X".format(record.advertiseFlags)}  ${parseFlags(record.advertiseFlags)}",
                    CLR_DIM)
            }

            // Service UUIDs
            val uuids = record.serviceUuids
            if (!uuids.isNullOrEmpty()) {
                sb.dim("  Service UUIDs  (${uuids.size})\n", CLR_LABEL)
                uuids.forEach { sb.dim("    $it\n", CLR_DIM) }
            }

            // Manufacturer Specific Data
            val mfr = record.manufacturerSpecificData
            val mfrExportLines = StringBuilder()
            if (mfr != null && mfr.size() > 0) {
                sb.dim("  Manufacturer Data\n", CLR_LABEL)
                for (i in 0 until mfr.size()) {
                    val id   = mfr.keyAt(i)
                    val data = mfr.valueAt(i)
                    val hex  = data.joinToString(" ") { "%02X".format(it) }
                    sb.row("    Company    ", "0x${"%04X".format(id)}  ${companyName(id)}", CLR_DIM)
                    sb.row("    HEX        ", hex, CLR_DIM)
                    mfrExportLines.append("  MFR[0x${"%04X".format(id)}/${companyName(id)}]: $hex\n")
                }
            }

            // Service Data
            val serviceData = record.serviceData
            if (!serviceData.isNullOrEmpty()) {
                sb.dim("  Service Data\n", CLR_LABEL)
                serviceData.forEach { (uuid, data) ->
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    sb.row("    UUID       ", "$uuid", CLR_DIM)
                    sb.row("    HEX        ", hex, CLR_DIM)
                }
            }

            // Raw bytes — HEX only
            record.bytes?.let { bytes ->
                val hex = bytes.take(31).joinToString(" ") { "%02X".format(it) }
                sb.dim("  Raw Bytes\n", CLR_LABEL)
                sb.dim("    $hex\n", CLR_LABEL)
            }

            // Build export line: timestamp + MFR hex only
            if (mfrExportLines.isNotEmpty()) {
                exportLog.append("[$timeStr]  delta:${deltaMs?.let { "+${it}ms" } ?: "first"}  rssi:${result.rssi}dBm\n")
                exportLog.append(mfrExportLines)
                exportLog.append("\n")
            }

        } else {
            sb.dim("  No advertisement payload\n", CLR_LABEL)
        }

        sb.dim("\n", CLR_LABEL)
        logView.append(sb)
        if (isListening) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun updateStats(rssi: Int, count: Int) {
        statPackets.text = "$count\nPackets"

        val med = medianDelta()
        if (med != null) {
            statMedian.text = "+${med}ms\nMedian Interval"
            statMedian.setTextColor(Color.parseColor("#90A4AE"))
        } else {
            statMedian.text = "—\nMedian Interval"
            statMedian.setTextColor(Color.parseColor("#455A64"))
        }

        statRssi.text = "${rssi}dBm\nRSSI"
        statRssi.setTextColor(rssiColor(rssi))
    }

    private fun medianDelta(): Long? {
        if (allDeltas.isEmpty()) return null
        val sorted = allDeltas.sorted()
        return if (sorted.size % 2 == 0)
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        else
            sorted[sorted.size / 2]
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    private fun clearLog() {
        logView.text = ""
        packetCount = 0
        lastPacketTimeMs = 0L
        allDeltas.clear()
        exportLog.clear()
        statPackets.text = "0\nPackets"
        statMedian.text  = "—\nMedian Interval"
        statMedian.setTextColor(Color.parseColor("#455A64"))
        statRssi.text    = "—\nRSSI"
        statRssi.setTextColor(Color.parseColor("#455A64"))
    }

    // ── Save / Share Log ──────────────────────────────────────────────────────

    private fun saveLog() {
        if (exportLog.isEmpty()) {
            Toast.makeText(this, "No manufacturer data to save yet", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dir = File(getExternalFilesDir(null), "ble_logs")
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val file  = File(dir, "ble_adv_${stamp}.txt")

            val header = buildString {
                appendLine("BLE Advertisement Log")
                appendLine("Device : $deviceName")
                appendLine("Address: $address")
                appendLine("Saved  : ${timeFmt.format(Date())}")
                appendLine("Packets: $packetCount   Median interval: ${medianDelta()?.let { "${it}ms" } ?: "—"}")
                appendLine("Format : [timestamp]  MFR[companyId/name]: HEX bytes")
                appendLine("=".repeat(60))
                appendLine()
            }

            file.writeText(header + exportLog.toString())

            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "BLE Log — $deviceName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "Save or Share Log"))

        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Span Helpers ──────────────────────────────────────────────────────────

    private fun SpannableStringBuilder.dim(text: String, color: Int) {
        val s = length; append(text)
        setSpan(ForegroundColorSpan(color), s, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.colored(text: String, color: Int) {
        val s = length; append(text)
        setSpan(ForegroundColorSpan(color), s, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD),   s, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.row(label: String, value: String, valueColor: Int) {
        dim(label, CLR_LABEL)
        dim("$value\n", valueColor)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun rssiBar(rssi: Int) = when {
        rssi >= -50 -> "▂▄▆█ Excellent"
        rssi >= -65 -> "▂▄▆  Good"
        rssi >= -75 -> "▂▄   Fair"
        rssi >= -85 -> "▂    Weak"
        else        -> "     Very Weak"
    }

    private fun parseFlags(flags: Int): String {
        val p = mutableListOf<String>()
        if (flags and 0x01 != 0) p.add("LE Limited")
        if (flags and 0x02 != 0) p.add("LE General")
        if (flags and 0x04 != 0) p.add("No BR/EDR")
        return p.joinToString(" · ")
    }

    private fun companyName(id: Int) = when (id) {
        0x004C -> "Apple"
        0x0006 -> "Microsoft"
        0x0075 -> "Samsung"
        0x00E0 -> "Google"
        0x0059 -> "Nordic Semiconductor"
        0x0131 -> "Espressif"
        0x000F -> "Broadcom"
        0x0002 -> "Intel"
        else   -> "Unknown"
    }
}
