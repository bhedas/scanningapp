package com.example.bleapp

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParsedLiveActivity : AppCompatActivity() {

    private lateinit var address: String
    private lateinit var deviceName: String
    private lateinit var config: ParserConfig

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    private lateinit var statusText: TextView
    private lateinit var packetCountText: TextView
    private lateinit var sourceLabel: TextView

    private val frameResults = mutableListOf<FrameResult>()
    private lateinit var adapter: FrameResultAdapter
    private var totalPackets = 0

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parsed_live)

        config = ParserConfigHolder.config ?: run {
            finish(); return
        }

        address    = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS) ?: ""
        deviceName = intent.getStringExtra(MainActivity.EXTRA_DEVICE_NAME) ?: "Device"

        supportActionBar?.title = "Live Parser"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        findViewById<TextView>(R.id.parsedDeviceName).text    = deviceName
        findViewById<TextView>(R.id.parsedDeviceAddress).text = address
        statusText      = findViewById(R.id.parsedStatusText)
        packetCountText = findViewById(R.id.parsedPacketCount)
        sourceLabel     = findViewById(R.id.sourceLabel)

        sourceLabel.text = "SOURCE: ${config.dataSource.label.uppercase()}"

        // Build one FrameResult per frame definition
        config.frames.forEach { frameResults.add(FrameResult(it)) }

        adapter = FrameResultAdapter(frameResults)
        val recycler = findViewById<RecyclerView>(R.id.parsedFramesRecycler)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        startListening()
    }

    override fun onResume()  { super.onResume(); startListening() }
    override fun onPause()   { super.onPause(); stopListening() }
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private fun startListening() {
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        val filter = ScanFilter.Builder().setDeviceAddress(address).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0).build()
        try {
            bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            statusText.text = "LIVE"
            statusText.setTextColor(Color.parseColor("#69F0AE"))
        } catch (e: SecurityException) {
            statusText.text = "NO PERMISSION"
            statusText.setTextColor(Color.parseColor("#FF1744"))
        }
    }

    private fun stopListening() {
        try { bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread { handlePacket(result) }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                statusText.text = "ERROR $errorCode"
                statusText.setTextColor(Color.parseColor("#FF1744"))
            }
        }
    }

    // ── Packet handling ───────────────────────────────────────────────────────

    private fun handlePacket(result: ScanResult) {
        val bytes = extractBytes(result) ?: return
        totalPackets++
        packetCountText.text = "$totalPackets packets"

        val bootMs   = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val pktMs    = bootMs + result.timestampNanos / 1_000_000
        val timeStr  = timeFmt.format(Date(pktMs))
        val rawHex   = bytes.joinToString(" ") { "%02X".format(it) }

        // Check all frame definitions — update ALL that match (Option B)
        frameResults.forEachIndexed { index, frameResult ->
            if (ParserEngine.matchesFilters(bytes, frameResult.definition.filters)) {
                frameResult.hasData      = true
                frameResult.lastUpdate   = timeStr
                frameResult.rawHex       = rawHex
                frameResult.parsedValues = ParserEngine.applyRules(bytes, frameResult.definition.rules)
                adapter.notifyItemChanged(index, "UPDATE")
            }
        }
    }

    // ── Byte extraction by data source ────────────────────────────────────────

    private fun extractBytes(result: ScanResult): ByteArray? {
        val record = result.scanRecord ?: return null
        return when (config.dataSource) {
            DataSource.MANUFACTURER -> {
                val mfr = record.manufacturerSpecificData
                if (mfr != null && mfr.size() > 0) mfr.valueAt(0) else null
            }
            DataSource.RAW -> record.bytes
            DataSource.SERVICE -> {
                val sd = record.serviceData
                if (!sd.isNullOrEmpty()) sd.values.first() else null
            }
        }
    }
}

// ── RecyclerView Adapter ──────────────────────────────────────────────────────

class FrameResultAdapter(
    private val results: List<FrameResult>
) : RecyclerView.Adapter<FrameResultAdapter.VH>() {

    inner class VH(val root: android.view.View) : RecyclerView.ViewHolder(root) {
        val nameText: TextView      = root.findViewById(R.id.frameCardName)
        val filtersText: TextView   = root.findViewById(R.id.frameCardFilters)
        val timestampText: TextView = root.findViewById(R.id.frameCardTimestamp)
        val valuesContainer: LinearLayout = root.findViewById(R.id.parsedValuesContainer)
        val rawText: TextView       = root.findViewById(R.id.frameCardRaw)
        // Fixed value row views created once in fullBind
        val valueRows: MutableList<Pair<TextView, TextView>> = mutableListOf()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parsed_frame_card, parent, false)
        return VH(view)
    }

    override fun getItemCount() = results.size

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            // Partial update — only refresh values, timestamp, raw
            refreshValues(holder, results[position])
        } else {
            fullBind(holder, results[position])
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        fullBind(holder, results[position])
    }

    private fun fullBind(holder: VH, result: FrameResult) {
        val def = result.definition

        holder.nameText.text    = def.name
        holder.filtersText.text = def.filters.joinToString("  ") {
            "byte[${it.byteIndex}]=0x${"%02X".format(it.value)}"
        }

        // Build fixed value rows (one per rule — structure never changes)
        holder.valuesContainer.removeAllViews()
        holder.valueRows.clear()

        def.rules.forEach { rule ->
            val rowView = LayoutInflater.from(holder.root.context)
                .inflate(R.layout.item_parsed_value_row, holder.valuesContainer, false)
            val labelTv  = rowView.findViewById<TextView>(R.id.valueLabel)
            val valueTv  = rowView.findViewById<TextView>(R.id.valueResult)
            labelTv.text = rule.label
            valueTv.text = "—"
            holder.valuesContainer.addView(rowView)
            holder.valueRows.add(Pair(labelTv, valueTv))
        }

        refreshValues(holder, result)
    }

    private fun refreshValues(holder: VH, result: FrameResult) {
        if (!result.hasData) {
            holder.timestampText.text = "waiting..."
            holder.rawText.text = "RAW: —"
            holder.valueRows.forEach { (_, valueTv) -> valueTv.text = "—" }
            return
        }

        holder.timestampText.text = result.lastUpdate
        holder.rawText.text = "RAW: ${result.rawHex}"

        result.parsedValues.forEachIndexed { i, pv ->
            if (i < holder.valueRows.size) {
                val display = if (pv.unit.isNotBlank()) "${pv.value}  ${pv.unit}" else pv.value
                holder.valueRows[i].second.text = display
            }
        }
    }
}
