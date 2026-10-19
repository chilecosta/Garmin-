package cl.garmin.hrmonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.*
import android.view.*
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val hrService = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val hrMeasurement = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var bpmView: TextView
    private lateinit var chart: HeartChart
    private lateinit var antView: TextView
    private lateinit var adapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var running = false
    private var lastDevice: BluetoothDevice? = null
    private val samples = mutableListOf<Pair<Long, Int>>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val scanStop = Runnable { stopScan(); if (gatt == null) status.text = "No se encontró sensor BLE. Comprueba transmisión del reloj." }
    private val reconnect = Runnable { if (!isFinishing && !scanning && gatt == null) startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24); setBackgroundColor(Color.rgb(15, 23, 42)) }
        fun label(text: String, size: Float): TextView = TextView(this).apply { this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER; setPadding(0, 12, 0, 12) }
        root.addView(label("GARMIN HR MONITOR", 22f))
        bpmView = label("-- BPM", 54f); root.addView(bpmView)
        status = label("Preparando conexión...", 15f); root.addView(status)
        chart = HeartChart(this); root.addView(chart, LinearLayout.LayoutParams(-1, 0, 1f))
        antView = label("", 13f); root.addView(antView)
        val connect = Button(this).apply { text = "Buscar / reconectar"; setOnClickListener { gatt?.close(); gatt = null; startScan() } }
        root.addView(connect)
        val export = Button(this).apply { text = "Compartir CSV"; setOnClickListener { exportCsv() } }
        root.addView(export)
        setContentView(root)
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter ?: run { status.text = "Este dispositivo no tiene Bluetooth"; return }
        val antPackages = listOf("com.dsi.ant.service.socket", "com.dsi.ant.plugins.antplus", "com.dsi.ant.server")
        val found = antPackages.filter { pkg -> try { packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false } }
        antView.text = if (found.isEmpty()) "ANT+: servicios no detectados. Esta versión lee sensores BLE Heart Rate." else "ANT+: servicios detectados (${found.joinToString()}). La recepción ANT+ aún requiere integración del SDK ANT+."
        requestAndStart()
    }
    private fun permissionsOk(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestAndStart() {
        if (!permissionsOk()) {
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            requestPermissions(permissions, 200)
        } else startScan()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) { if (permissionsOk()) startScan() else status.text = "Permisos Bluetooth necesarios" }
    }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.scanRecord?.serviceUuids?.any { it.uuid == hrService } == true) {
                stopScan(); lastDevice = result.device; status.text = "Conectando al sensor..."
                try { gatt = result.device.connectGatt(this@MainActivity, false, gattCallback) } catch (_: SecurityException) { status.text = "Permiso Bluetooth denegado" }
            }
        }
        override fun onScanFailed(errorCode: Int) { scanning = false; status.text = "Error de búsqueda BLE: $errorCode" }
    }
    private fun startScan() {
        if (!permissionsOk()) { requestAndStart(); return }
        if (!adapter.isEnabled) { status.text = "Activa Bluetooth y pulsa Buscar"; return }
        if (scanning) return
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) { status.text = "BLE no disponible"; return }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(hrService)).build())
        try { scanner?.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback); scanning = true; status.text = "Buscando sensor cardíaco BLE..."; handler.postDelayed(scanStop, 15000) }
        catch (_: SecurityException) { status.text = "Faltan permisos Bluetooth" }
    }
    private fun stopScan() {
        handler.removeCallbacks(scanStop)
        if (scanning) try { scanner?.stopScan(callback) } catch (_: Exception) { }
        scanning = false
    }
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { status.text = "Conectado; leyendo servicio cardíaco..." }
                try { g.discoverServices() } catch (_: SecurityException) { }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close(); if (gatt === g) gatt = null
                runOnUiThread { status.text = "Desconectado. Reintentando..."; handler.removeCallbacks(reconnect); handler.postDelayed(reconnect, 3000) }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val characteristic = g.getService(hrService)?.getCharacteristic(hrMeasurement)
            if (characteristic == null) { runOnUiThread { status.text = "Servicio cardíaco no disponible" }; return }
            try {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(cccd)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
                }
                runOnUiThread { status.text = "Esperando pulsaciones..." }
            } catch (_: SecurityException) { runOnUiThread { status.text = "Permiso Bluetooth denegado" } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { if (characteristic.uuid == hrMeasurement) onHeartRate(value) }
        @Deprecated("Legacy callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { if (Build.VERSION.SDK_INT < 33 && characteristic.uuid == hrMeasurement) onHeartRate(characteristic.value ?: return) }
    }
    private fun onHeartRate(bytes: ByteArray) {
        if (bytes.size < 2) return
        val bpm = if (bytes[0].toInt() and 1 == 0) bytes[1].toInt() and 0xff else if (bytes.size >= 3) (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8) else return
        if (bpm !in 25..250) return
        synchronized(samples) { samples.add(System.currentTimeMillis() to bpm); if (samples.size > 1800) samples.removeAt(0) }
        runOnUiThread {
            bpmView.text = "$bpm BPM"; bpmView.setTextColor(when { bpm < 100 -> Color.rgb(74, 222, 128); bpm < 150 -> Color.rgb(250, 204, 21); else -> Color.rgb(248, 113, 113) })
            status.text = "Recibiendo frecuencia cardíaca"; chart.values = synchronized(samples) { samples.takeLast(240).map { it.second } }; chart.invalidate()
        }
    }
    private fun exportCsv() {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.isEmpty()) { Toast.makeText(this, "Aún no hay mediciones", Toast.LENGTH_SHORT).show(); return }
        val csv = buildString { append("timestamp,bpm\n"); snapshot.forEach { (t, bpm) -> append("${dateFormat.format(Date(t))},$bpm\n") } }
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/csv"; putExtra(Intent.EXTRA_TEXT, csv); putExtra(Intent.EXTRA_SUBJECT, "Garmin HR Monitor - mediciones") }
        startActivity(Intent.createChooser(send, "Compartir mediciones CSV"))
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); stopScan(); try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}; super.onDestroy() }
}
class HeartChart(context: Context) : View(context) {
    var values: List<Int> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(56, 189, 248); strokeWidth = 5f; style = Paint.Style.STROKE }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.size < 2) return
        val min = (values.minOrNull() ?: 50) - 10; val max = (values.maxOrNull() ?: 150) + 10
        val path = android.graphics.Path()
        values.forEachIndexed { index, value ->
            val x = index * width.toFloat() / (values.size - 1)
            val y = height - (value - min).toFloat() / (max - min).coerceAtLeast(1) * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
