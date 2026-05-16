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
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdvertisementActivity : AppCompatActivity() {

    private lateinit var address: String
    private lateinit var deviceName: String

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private lateinit var deviceNameText: TextView
    private lateinit var deviceAddressText: TextView
    private lateinit var scanStatusDot: TextView
    private lateinit var statPackets: TextView
    private lateinit var statDelta: TextView
    private lateinit var statRssi: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var stopButton: MaterialButton
    private lateinit var clearButton: MaterialButton
    private lateinit var connectButton: MaterialButton

    private var packetCount = 0
    private var lastPacketTimeMs = 0L
    private var isListening = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ── Color Palette ─────────────────────────────────────────────────────────
    companion object {
        val CLR_DIVIDER   = Color.parseColor("#1A2327")
        val CLR_PKT_NUM   = Color.parseColor("#455A64")
        val CLR_TIMESTAMP = Color.parseColor("#80DEEA")
        val CLR_LABEL     = Color.parseColor("#546E7A")
        val CLR_NAME      = Color.parseColor("#FFFFFF")
        val CLR_TXPOWER   = Color.parseColor("#80CBC4")
        val CLR_FLAGS     = Color.parseColor("#CE93D8")
        val CLR_UUID      = Color.parseColor("#82B1FF")
        val CLR_MFR_ID    = Color.parseColor("#FFCC80")
        val CLR_MFR_DATA  = Color.parseColor("#FFE0B2")
        val CLR_RAW       = Color.parseColor("#455A64")

        fun rssiColor(rssi: Int) = when {
            rssi >= -50 -> Color.parseColor("#69F0AE")
            rssi >= -65 -> Color.parseColor("#B2FF59")
            rssi >= -75 -> Color.parseColor("#FFD740")
            rssi >= -85 -> Color.parseColor("#FF6D00")
            else        -> Color.parseColor("#FF1744")
        }

        fun deltaColor(ms: Long) = when {
            ms < 200  -> Color.parseColor("#69F0AE")
            ms < 1000 -> Color.parseColor("#FFD54F")
            else      -> Color.parseColor("#FF7043")
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

        deviceNameText    = findViewById(R.id.deviceNameText)
        deviceAddressText = findViewById(R.id.deviceAddressText)
        scanStatusDot     = findViewById(R.id.scanStatusDot)
        statPackets       = findViewById(R.id.statPackets)
        statDelta         = findViewById(R.id.statDelta)
        statRssi          = findViewById(R.id.statRssi)
        logView           = findViewById(R.id.advLogView)
        logScroll         = findViewById(R.id.advLogScroll)
        stopButton        = findViewById(R.id.stopButton)
        clearButton       = findViewById(R.id.clearButton)
        connectButton     = findViewById(R.id.connectButton)

        deviceNameText.text    = deviceName
        deviceAddressText.text = address

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        stopButton.setOnClickListener {
            if (isListening) pauseListening() else resumeListening()
        }
        clearButton.setOnClickListener {
            logView.text = ""
            packetCount = 0
            lastPacketTimeMs = 0L
            resetStats()
        }
        connectButton.setOnClickListener {
            startActivity(Intent(this, DeviceActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, address)
                putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
            })
        }

        startListening()
    }

    override fun onResume() {
        super.onResume()
        if (!isListening) resumeListening()
    }

    override fun onPause() {
        super.onPause()
        pauseListening()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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
            scanStatusDot.text = "● Permission denied"
            scanStatusDot.setTextColor(Color.parseColor("#FF1744"))
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
            scanStatusDot.text = "● Live"
            scanStatusDot.setTextColor(Color.parseColor("#69F0AE"))
            stopButton.text = "⏸  Stop"
            stopButton.setBackgroundColor(Color.parseColor("#B71C1C"))
        } else {
            scanStatusDot.text = "⏸  Paused — scroll to copy data"
            scanStatusDot.setTextColor(Color.parseColor("#FFD54F"))
            stopButton.text = "▶  Resume"
            stopButton.setBackgroundColor(Color.parseColor("#1B5E20"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { logAdvertisement(result) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanStatusDot.text = "● Scan error ($errorCode)"
                scanStatusDot.setTextColor(Color.parseColor("#FF1744"))
            }
        }
    }

    // ── Advertisement Logger ──────────────────────────────────────────────────

    private fun logAdvertisement(result: ScanResult) {
        packetCount++

        // Accurate timestamp
        val bootTimeMs   = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val packetTimeMs = bootTimeMs + result.timestampNanos / 1_000_000
        val timeStr      = timeFormat.format(Date(packetTimeMs))

        // Delta
        val deltaMs = if (lastPacketTimeMs == 0L) null else packetTimeMs - lastPacketTimeMs
        lastPacketTimeMs = packetTimeMs

        updateStats(result.rssi, deltaMs ?: 0L, packetCount)

        val sb = SpannableStringBuilder()

        // Divider
        sb.clr("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n", CLR_DIVIDER)

        // Packet number
        sb.clr("  #$packetCount\n", CLR_PKT_NUM, bold = true)

        // Timestamp + Delta
        sb.clr("  ⏱  ", CLR_LABEL)
        sb.clr(timeStr, CLR_TIMESTAMP, bold = true)
        if (deltaMs != null) {
            sb.clr("   Δ +${deltaMs}ms\n", deltaColor(deltaMs), bold = true)
        } else {
            sb.clr("   Δ first packet\n", CLR_LABEL)
        }

        // RSSI
        sb.clr("  📶 ", CLR_LABEL)
        sb.clr("RSSI     ", CLR_LABEL)
        sb.clr("${result.rssi} dBm   ${rssiBar(result.rssi)}\n", rssiColor(result.rssi), bold = true)

        val record = result.scanRecord
        if (record != null) {

            // Local Name
            record.deviceName?.takeIf { it.isNotBlank() }?.let {
                sb.row("  📛 ", "Name     ", it, CLR_NAME, bold = true)
            }

            // TX Power
            if (record.txPowerLevel != Int.MIN_VALUE) {
                sb.row("  📡 ", "TX Power ", "${record.txPowerLevel} dBm", CLR_TXPOWER)
            }

            // Flags
            if (record.advertiseFlags != -1) {
                sb.row("  🚩 ", "Flags    ",
                    "0x${"%02X".format(record.advertiseFlags)}  ${parseFlags(record.advertiseFlags)}",
                    CLR_FLAGS)
            }

            // Service UUIDs
            val uuids = record.serviceUuids
            if (!uuids.isNullOrEmpty()) {
                sb.clr("  🔧 ", CLR_LABEL)
                sb.clr("Service UUIDs  (${uuids.size})\n", CLR_LABEL)
                uuids.forEach { uuid ->
                    sb.clr("       • ", CLR_LABEL)
                    sb.clr("$uuid\n", CLR_UUID)
                }
            }

            // Manufacturer Specific Data
            val mfr = record.manufacturerSpecificData
            if (mfr != null && mfr.size() > 0) {
                sb.clr("  🏭 ", CLR_LABEL)
                sb.clr("Manufacturer Data\n", CLR_LABEL)
                for (i in 0 until mfr.size()) {
                    val id   = mfr.keyAt(i)
                    val data = mfr.valueAt(i)
                    val hex  = data.joinToString(" ") { "%02X".format(it) }
                    sb.clr("       Company   ", CLR_LABEL)
                    sb.clr("0x${"%04X".format(id)}", CLR_MFR_ID, bold = true)
                    sb.clr("  ${companyName(id)}\n", CLR_LABEL)
                    sb.clr("       HEX       ", CLR_LABEL)
                    sb.clr("$hex\n", CLR_MFR_DATA)
                }
            }

            // Service Data
            val serviceData = record.serviceData
            if (!serviceData.isNullOrEmpty()) {
                sb.clr("  📦 ", CLR_LABEL)
                sb.clr("Service Data\n", CLR_LABEL)
                serviceData.forEach { (uuid, data) ->
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    sb.clr("       UUID      ", CLR_LABEL)
                    sb.clr("$uuid\n", CLR_UUID)
                    sb.clr("       HEX       ", CLR_LABEL)
                    sb.clr("$hex\n", CLR_MFR_DATA)
                }
            }

            // Raw bytes (HEX only)
            record.bytes?.let { bytes ->
                val hex = bytes.take(31).joinToString(" ") { "%02X".format(it) }
                sb.clr("  🔢 ", CLR_LABEL)
                sb.clr("Raw Bytes\n", CLR_LABEL)
                sb.clr("       $hex\n", CLR_RAW)
            }

        } else {
            sb.clr("  ⚠  No advertisement payload\n", Color.parseColor("#FF7043"))
        }

        sb.clr("\n", CLR_LABEL)

        logView.append(sb)
        if (isListening) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private fun updateStats(rssi: Int, deltaMs: Long, count: Int) {
        statPackets.text = "$count\nPackets"
        if (deltaMs > 0) {
            statDelta.text = "+${deltaMs}ms\nΔ Interval"
            statDelta.setTextColor(deltaColor(deltaMs))
        } else {
            statDelta.text = "—\nΔ Interval"
            statDelta.setTextColor(Color.parseColor("#607D8B"))
        }
        if (rssi != 0) {
            statRssi.text = "${rssi}dBm\nRSSI"
            statRssi.setTextColor(rssiColor(rssi))
        }
    }

    private fun resetStats() {
        statPackets.text = "0\nPackets"
        statDelta.text = "—\nΔ Interval"
        statDelta.setTextColor(Color.parseColor("#607D8B"))
        statRssi.text = "—\nRSSI"
        statRssi.setTextColor(Color.parseColor("#607D8B"))
    }

    // ── SpannableStringBuilder helpers ────────────────────────────────────────

    private fun SpannableStringBuilder.clr(text: String, color: Int, bold: Boolean = false) {
        val start = length
        append(text)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) setSpan(StyleSpan(Typeface.BOLD), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun SpannableStringBuilder.row(
        icon: String, label: String, value: String,
        valueColor: Int, bold: Boolean = false
    ) {
        clr(icon, CLR_LABEL)
        clr(label, CLR_LABEL)
        clr("$value\n", valueColor, bold)
    }

    // ── Display Helpers ───────────────────────────────────────────────────────

    private fun rssiBar(rssi: Int) = when {
        rssi >= -50 -> "▂▄▆█ Excellent"
        rssi >= -65 -> "▂▄▆  Good"
        rssi >= -75 -> "▂▄   Fair"
        rssi >= -85 -> "▂    Weak"
        else        -> "     Very Weak"
    }

    private fun parseFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x01 != 0) parts.add("LE Limited")
        if (flags and 0x02 != 0) parts.add("LE General")
        if (flags and 0x04 != 0) parts.add("No BR/EDR")
        return parts.joinToString(" · ")
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
