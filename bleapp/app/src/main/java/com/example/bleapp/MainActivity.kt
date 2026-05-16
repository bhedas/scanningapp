package com.example.bleapp

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private val SCAN_PERIOD = 15_000L
    private val devices = mutableListOf<ScanResult>()
    private lateinit var deviceAdapter: DeviceAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var scanButton: MaterialButton
    private lateinit var progressIndicator: LinearProgressIndicator
    private lateinit var emptyView: TextView

    companion object {
        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_DEVICE_NAME = "device_name"
        private const val PERMISSION_REQUEST_CODE = 100

        private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.title = "BLE Scanner"

        recyclerView = findViewById(R.id.recyclerView)
        scanButton = findViewById(R.id.scanButton)
        progressIndicator = findViewById(R.id.progressIndicator)
        emptyView = findViewById(R.id.emptyView)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        deviceAdapter = DeviceAdapter(devices) { result ->
            val name = try { result.device.name } catch (e: SecurityException) { null }
            val intent = Intent(this, AdvertisementActivity::class.java).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, result.device.address)
                putExtra(EXTRA_DEVICE_NAME, name ?: "Unknown Device")
            }
            startActivity(intent)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = deviceAdapter
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        scanButton.setOnClickListener {
            if (scanning) stopScan() else checkPermissionsAndScan()
        }
    }

    private fun checkPermissionsAndScan() {
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth first", Toast.LENGTH_LONG).show()
            return
        }
        val missing = REQUIRED_PERMISSIONS.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startScan()
        else ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun startScan() {
        devices.clear()
        deviceAdapter.notifyDataSetChanged()
        emptyView.visibility = View.GONE

        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        scanning = true
        scanButton.text = "Stop Scanning"
        progressIndicator.visibility = View.VISIBLE

        handler.postDelayed({ stopScan() }, SCAN_PERIOD)

        try {
            bluetoothLeScanner?.startScan(scanCallback)
        } catch (e: SecurityException) {
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_SHORT).show()
            stopScan()
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanButton.text = "Start Scan"
        progressIndicator.visibility = View.GONE
        handler.removeCallbacksAndMessages(null)
        try { bluetoothLeScanner?.stopScan(scanCallback) } catch (e: SecurityException) { }
        if (devices.isEmpty()) emptyView.visibility = View.VISIBLE
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val index = devices.indexOfFirst { it.device.address == result.device.address }
            if (index == -1) {
                devices.add(result)
                deviceAdapter.notifyItemInserted(devices.size - 1)
            } else {
                devices[index] = result
                deviceAdapter.notifyItemChanged(index)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Toast.makeText(this@MainActivity, "Scan failed (error $errorCode)", Toast.LENGTH_SHORT).show()
            stopScan()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) startScan()
            else Toast.makeText(this, "Bluetooth permissions are required to scan", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }
}

// ── Adapter ────────────────────────────────────────────────────────────────

class DeviceAdapter(
    private val items: List<ScanResult>,
    private val onConnect: (ScanResult) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.deviceName)
        val address: TextView = view.findViewById(R.id.deviceAddress)
        val rssi: TextView = view.findViewById(R.id.deviceRssi)
        val connectBtn: MaterialButton = view.findViewById(R.id.connectBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val result = items[position]
        holder.name.text = try { result.device.name?.takeIf { it.isNotBlank() } ?: "Unknown Device" }
                           catch (e: SecurityException) { "Unknown Device" }
        holder.address.text = result.device.address
        holder.rssi.text = "RSSI: ${result.rssi} dBm"
        holder.connectBtn.setOnClickListener { onConnect(result) }
        holder.itemView.setOnClickListener { onConnect(result) }
    }
}
