package com.example.bleapp

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.UUID

class DeviceActivity : AppCompatActivity() {

    private var gatt: BluetoothGatt? = null
    private lateinit var address: String
    private lateinit var deviceName: String

    private lateinit var statusText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var charRecycler: RecyclerView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var writeSection: LinearLayout
    private lateinit var writeInput: TextInputEditText
    private lateinit var writeButton: MaterialButton
    private lateinit var writeHexCheckbox: CheckBox

    private val characteristics = mutableListOf<CharEntry>()
    private lateinit var charAdapter: CharacteristicAdapter
    private var selectedChar: BluetoothGattCharacteristic? = null

    data class CharEntry(
        val characteristic: BluetoothGattCharacteristic,
        val serviceName: String,
        val isHeader: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        address = intent.getStringExtra(MainActivity.EXTRA_DEVICE_ADDRESS) ?: ""
        deviceName = intent.getStringExtra(MainActivity.EXTRA_DEVICE_NAME) ?: "Device"

        supportActionBar?.title = deviceName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusText     = findViewById(R.id.statusText)
        connectButton  = findViewById(R.id.connectButton)
        charRecycler   = findViewById(R.id.charRecycler)
        logView        = findViewById(R.id.logView)
        logScroll      = findViewById(R.id.logScroll)
        writeSection   = findViewById(R.id.writeSection)
        writeInput     = findViewById(R.id.writeInput)
        writeButton    = findViewById(R.id.writeButton)
        writeHexCheckbox = findViewById(R.id.writeHexCheckbox)

        charAdapter = CharacteristicAdapter(characteristics) { entry ->
            selectedChar = entry.characteristic
            onCharacteristicSelected(entry.characteristic)
        }
        charRecycler.layoutManager = LinearLayoutManager(this)
        charRecycler.adapter = charAdapter
        charRecycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))

        connectButton.setOnClickListener {
            if (gatt == null) connectDevice() else disconnectDevice()
        }

        writeButton.setOnClickListener {
            val text = writeInput.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isEmpty()) { log("⚠ Enter data to send"); return@setOnClickListener }
            sendData(text)
        }
    }

    // ── Connection ────────────────────────────────────────────────────────────

    private fun connectDevice() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = btManager.adapter.getRemoteDevice(address)
        setStatus("Connecting…", R.color.status_connecting)
        connectButton.isEnabled = false
        log("Connecting to $deviceName ($address)…")
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            log("❌ Permission denied: ${e.message}")
            connectButton.isEnabled = true
        }
    }

    private fun disconnectDevice() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: SecurityException) { }
        gatt = null
        setStatus("Disconnected", R.color.status_disconnected)
        connectButton.text = "Connect"
        connectButton.isEnabled = true
        characteristics.clear()
        charAdapter.notifyDataSetChanged()
        writeSection.visibility = View.GONE
        log("Disconnected.")
    }

    // ── GATT Callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        setStatus("Connected", R.color.status_connected)
                        connectButton.text = "Disconnect"
                        connectButton.isEnabled = true
                        log("✅ Connected. Discovering services…")
                        try { gatt.discoverServices() } catch (e: SecurityException) { log("❌ Permission denied") }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        setStatus("Disconnected", R.color.status_disconnected)
                        connectButton.text = "Connect"
                        connectButton.isEnabled = true
                        this@DeviceActivity.gatt = null
                        log("🔌 Disconnected (status=$status)")
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("❌ Service discovery failed (status=$status)"); return@runOnUiThread
                }
                characteristics.clear()
                gatt.services.forEach { service ->
                    service.characteristics.forEach { char ->
                        characteristics.add(CharEntry(char, shortUuid(service.uuid)))
                    }
                }
                charAdapter.notifyDataSetChanged()
                log("🔍 Found ${gatt.services.size} services, ${characteristics.size} characteristics")
            }
        }

        // API 33+
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            runOnUiThread { handleRead(characteristic, value, status) }
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread { handleRead(characteristic, characteristic.value ?: byteArrayOf(), status) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS)
                    log("✅ Write OK → [${shortUuid(characteristic.uuid)}]")
                else
                    log("❌ Write failed (status=$status)")
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            runOnUiThread { log("🔔 Notify [${shortUuid(characteristic.uuid)}]: ${formatBytes(value)}") }
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread {
                    log("🔔 Notify [${shortUuid(characteristic.uuid)}]: ${formatBytes(characteristic.value ?: byteArrayOf())}")
                }
            }
        }
    }

    // ── Read / Write ──────────────────────────────────────────────────────────

    private fun onCharacteristicSelected(char: BluetoothGattCharacteristic) {
        val props = char.properties
        val canRead   = props and BluetoothGattCharacteristic.PROPERTY_READ != 0
        val canWrite  = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                        props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val canNotify = props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

        log("Selected [${shortUuid(char.uuid)}]  R:$canRead W:$canWrite N:$canNotify")

        if (canRead) {
            try { gatt?.readCharacteristic(char); log("📖 Reading…") }
            catch (e: SecurityException) { log("❌ Permission denied") }
        }
        if (canNotify) enableNotifications(char)
        if (canWrite) writeSection.visibility = View.VISIBLE
        else writeSection.visibility = View.GONE
    }

    private fun enableNotifications(char: BluetoothGattCharacteristic) {
        try {
            gatt?.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt?.writeDescriptor(descriptor)
                }
                log("🔔 Notifications enabled for [${shortUuid(char.uuid)}]")
            }
        } catch (e: SecurityException) { log("❌ Permission denied") }
    }

    private fun sendData(text: String) {
        val char = selectedChar ?: run { log("⚠ No characteristic selected"); return }
        val bytes = if (writeHexCheckbox.isChecked) hexStringToBytes(text) else text.toByteArray(Charsets.UTF_8)
        if (bytes == null) { log("⚠ Invalid hex string"); return }

        try {
            val writeType = if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt?.writeCharacteristic(char, bytes, writeType)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = writeType
                @Suppress("DEPRECATION")
                char.value = bytes
                @Suppress("DEPRECATION")
                gatt?.writeCharacteristic(char)
            }
            log("📤 Sending: \"$text\" (${bytes.size} bytes)")
        } catch (e: SecurityException) { log("❌ Permission denied") }
    }

    private fun handleRead(char: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            log("📥 Read [${shortUuid(char.uuid)}]: ${formatBytes(value)}")
            writeSection.visibility = View.VISIBLE
        } else {
            log("❌ Read failed (status=$status)")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: ByteArray): String {
        val hex = bytes.joinToString(" ") { "%02X".format(it) }
        val ascii = bytes.map { if (it in 32..126) it.toInt().toChar() else '.' }.joinToString("")
        return "HEX[$hex]  ASCII[$ascii]"
    }

    private fun hexStringToBytes(hex: String): ByteArray? {
        val clean = hex.replace(" ", "")
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) { null }
    }

    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.length == 36 && s.substring(8).lowercase() == "-0000-1000-8000-00805f9b34fb")
            "0x${s.substring(4, 8).uppercase()}"
        else s
    }

    private fun setStatus(text: String, colorRes: Int) {
        statusText.text = text
        statusText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun log(msg: String) {
        logView.append("$msg\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        try { gatt?.disconnect(); gatt?.close() } catch (e: SecurityException) { }
    }
}

// ── Characteristic Adapter ────────────────────────────────────────────────

class CharacteristicAdapter(
    private val items: List<DeviceActivity.CharEntry>,
    private val onClick: (DeviceActivity.CharEntry) -> Unit
) : RecyclerView.Adapter<CharacteristicAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val uuid: TextView = view.findViewById(R.id.charUuid)
        val props: TextView = view.findViewById(R.id.charProps)
        val service: TextView = view.findViewById(R.id.charService)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_characteristic, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val char = entry.characteristic
        holder.service.text = "Service: ${entry.serviceName}"
        holder.uuid.text = shortUuid(char.uuid)
        val propList = buildList {
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE NR")
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        }
        holder.props.text = propList.joinToString("  |  ")
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    private fun shortUuid(uuid: UUID): String {
        val s = uuid.toString()
        return if (s.length == 36 && s.substring(8).lowercase() == "-0000-1000-8000-00805f9b34fb")
            "0x${s.substring(4, 8).uppercase()}"
        else s
    }
}
