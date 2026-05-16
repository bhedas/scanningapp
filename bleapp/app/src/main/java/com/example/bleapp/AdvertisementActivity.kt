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
import android.os.Bundle
import android.os.SystemClock
import android.view.View
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
    private lateinit var packetCountText: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var clearButton: MaterialButton
    private lateinit var connectButton: MaterialButton
    private lateinit var scanStatusText: TextView

    private var packetCount = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advertisement)

        address    = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS) ?: ""
        deviceName = intent.getStringExtra(MainActivity.EXTRA_DEVICE_NAME) ?: "Unknown Device"

        supportActionBar?.title = "Advertisement Data"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        deviceNameText    = findViewById(R.id.deviceNameText)
        deviceAddressText = findViewById(R.id.deviceAddressText)
        packetCountText   = findViewById(R.id.packetCountText)
        logView           = findViewById(R.id.advLogView)
        logScroll         = findViewById(R.id.advLogScroll)
        clearButton       = findViewById(R.id.clearButton)
        connectButton     = findViewById(R.id.connectButton)
        scanStatusText    = findViewById(R.id.scanStatusText)

        deviceNameText.text    = deviceName
        deviceAddressText.text = address

        clearButton.setOnClickListener {
            logView.text = ""
            packetCount = 0
            packetCountText.text = "Packets: 0"
        }

        connectButton.setOnClickListener {
            val intent = Intent(this, DeviceActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_DEVICE_ADDRESS, address)
                putExtra(MainActivity.EXTRA_DEVICE_NAME, deviceName)
            }
            startActivity(intent)
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        startListening()
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    private fun startListening() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        val filter = ScanFilter.Builder()
            .setDeviceAddress(address)
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            scanStatusText.text = "● Listening for advertisements…"
            scanStatusText.setTextColor(getColor(R.color.status_connected))
        } catch (e: SecurityException) {
            scanStatusText.text = "● Permission denied"
            scanStatusText.setTextColor(getColor(R.color.status_disconnected))
        }
    }

    private fun stopListening() {
        try { bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { }
        scanStatusText.text = "● Stopped"
        scanStatusText.setTextColor(getColor(R.color.status_disconnected))
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { logAdvertisement(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanStatusText.text = "● Scan error ($errorCode)"
                scanStatusText.setTextColor(getColor(R.color.status_disconnected))
            }
        }
    }

    // ── Advertisement Parser ──────────────────────────────────────────────────

    private fun logAdvertisement(result: ScanResult) {
        packetCount++
        packetCountText.text = "Packets: $packetCount"

        // Convert timestamp: result.timestampNanos is nanoseconds since device boot
        val bootTimeMs  = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val packetTimeMs = bootTimeMs + (result.timestampNanos / 1_000_000)
        val timeStr = timeFormat.format(Date(packetTimeMs))

        val sb = StringBuilder()
        sb.appendLine("┌─────────────────────────────────────")
        sb.appendLine("│ ⏱  Timestamp : $timeStr")
        sb.appendLine("│ 📶 RSSI      : ${result.rssi} dBm  ${rssiBar(result.rssi)}")

        val record = result.scanRecord
        if (record != null) {

            // Device local name
            val localName = record.deviceName
            if (!localName.isNullOrBlank()) {
                sb.appendLine("│ 📛 Local Name: $localName")
            }

            // TX Power
            if (record.txPowerLevel != Int.MIN_VALUE) {
                sb.appendLine("│ 📡 TX Power  : ${record.txPowerLevel} dBm")
            }

            // Advertise flags
            if (record.advertiseFlags != -1) {
                sb.appendLine("│ 🚩 Flags     : 0x${"%02X".format(record.advertiseFlags)} ${parseFlags(record.advertiseFlags)}")
            }

            // Service UUIDs
            val uuids = record.serviceUuids
            if (!uuids.isNullOrEmpty()) {
                sb.appendLine("│ 🔧 Service UUIDs (${uuids.size}):")
                uuids.forEach { sb.appendLine("│    • $it") }
            }

            // Manufacturer Specific Data
            val mfr = record.manufacturerSpecificData
            if (mfr != null && mfr.size() > 0) {
                sb.appendLine("│ 🏭 Manufacturer Data:")
                for (i in 0 until mfr.size()) {
                    val companyId = mfr.keyAt(i)
                    val data      = mfr.valueAt(i)
                    val hex       = data.joinToString(" ") { "%02X".format(it) }
                    val ascii     = data.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
                    sb.appendLine("│    Company ID : 0x${"%04X".format(companyId)} (${companyName(companyId)})")
                    sb.appendLine("│    HEX        : $hex")
                    sb.appendLine("│    ASCII      : $ascii")
                }
            }

            // Service Data
            val serviceData = record.serviceData
            if (serviceData != null && serviceData.isNotEmpty()) {
                sb.appendLine("│ 📦 Service Data:")
                serviceData.forEach { (uuid, data) ->
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    sb.appendLine("│    UUID : $uuid")
                    sb.appendLine("│    Data : $hex")
                }
            }

            // Raw advertisement bytes
            val raw = record.bytes
            if (raw != null) {
                val hex = raw.take(31).joinToString(" ") { "%02X".format(it) }
                sb.appendLine("│ 🔢 Raw Bytes  : $hex")
            }

        } else {
            sb.appendLine("│ ⚠ No advertisement data available")
        }

        sb.appendLine("└─────────────────────────────────────")
        sb.appendLine()

        logView.append(sb.toString())

        // Auto scroll to bottom
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun rssiBar(rssi: Int): String {
        return when {
            rssi >= -50 -> "▂▄▆█ Excellent"
            rssi >= -65 -> "▂▄▆  Good"
            rssi >= -75 -> "▂▄   Fair"
            rssi >= -85 -> "▂    Weak"
            else        -> "     Very Weak"
        }
    }

    private fun parseFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if (flags and 0x01 != 0) parts.add("LE Limited")
        if (flags and 0x02 != 0) parts.add("LE General")
        if (flags and 0x04 != 0) parts.add("BR/EDR Off")
        if (flags and 0x08 != 0) parts.add("LE+BR/EDR Controller")
        if (flags and 0x10 != 0) parts.add("LE+BR/EDR Host")
        return if (parts.isEmpty()) "" else "[${parts.joinToString(", ")}]"
    }

    private fun companyName(id: Int): String {
        return when (id) {
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        startListening()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
