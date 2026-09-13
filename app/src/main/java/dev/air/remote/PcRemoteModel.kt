package dev.air.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.core.content.edit
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

@SuppressLint("MissingPermission") // Every entry point checks runtime permission; revocation is caught as well.
class PcRemoteModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
    private var hid: BluetoothHidDevice? = null
    private var requesting = false
    private var registering = false
    private var foreground = false
    private var enabled = false
    private var closed = false
    private var host: BluetoothDevice? = null
    private var output: PcHidOutput? = null
    var registered by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set
    var status by mutableStateOf("Подключи компьютер по Bluetooth")
        private set
    var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
        private set
    var dragging by mutableStateOf(false)
        private set
    var modifiers by mutableIntStateOf(0)
        private set
    private val prefs = application.getSharedPreferences("pc_remote", Context.MODE_PRIVATE)
    var sensitivity by mutableFloatStateOf(prefs.getFloat("sensitivity", 1.6f))
        private set

    fun updateSensitivity(value: Float) {
        sensitivity = value.coerceIn(0.6f, 3.0f)
        prefs.edit { putFloat("sensitivity", sensitivity) }
    }

    fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: SecurityException) {
            connected = false
            status = "Разреши доступ к Bluetooth и подключись снова"
        } catch (_: IllegalStateException) {
            connected = false
            status = "Bluetooth недоступен. Включи его и повтори подключение"
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            registering = false
            if (!isRegistered) {
                output?.close(); output = null
                host = null
                connected = false
                modifiers = 0
                dragging = false
                status = "HID отключён. Нажми «Подключить», чтобы повторить"
            } else if (!foreground || !enabled) {
                safely { hid?.unregisterApp() }
            } else {
                status = "Готов к сопряжению с Windows"
                refreshDevices()
                val previous = prefs.getString("host", null)
                (pluggedDevice ?: devices.firstOrNull { it.address == previous })?.let(::connect)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) = safely {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    output?.close()
                    val profile = hid
                    output = PcHidOutput { id, report ->
                        val accepted = try { profile?.sendReport(device, id, report) == true }
                            catch (_: SecurityException) { false }
                            catch (_: IllegalStateException) { false }
                        if (!accepted) context.mainExecutor.execute {
                            if (host == device && connected) status = "Команда не отправлена. Переподключи ПК"
                        }
                    }
                    host = device
                    connected = true
                    status = "${device.name ?: "Компьютер"} · подключён"
                    prefs.edit { putString("host", device.address) }
                    releaseInputs()
                }
                BluetoothProfile.STATE_CONNECTING -> status = "Подключаемся к ${device.name ?: "компьютеру"}…"
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    output?.close(); output = null
                    host = null
                    connected = false
                    modifiers = 0
                    dragging = false
                    status = "Соединение закрыто. Выбери компьютер для подключения"
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) = safely {
            val report = when {
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == PcHidReports.KEYBOARD -> PcHidReports.keyboard()
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == PcHidReports.MOUSE -> PcHidReports.mouse(if (dragging) 1 else 0)
                type == BluetoothHidDevice.REPORT_TYPE_INPUT && id.toInt() == PcHidReports.CONSUMER -> PcHidReports.consumer()
                type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == PcHidReports.KEYBOARD -> byteArrayOf(0)
                else -> null
            }
            if (report != null) hid?.replyReport(device, type, id, if (bufferSize > 0) report.take(bufferSize).toByteArray() else report)
            else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = safely {
            hid?.reportError(device, if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == PcHidReports.KEYBOARD)
                BluetoothHidDevice.ERROR_RSP_SUCCESS else BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (closed) { adapter?.closeProfileProxy(profile, proxy); return }
            requesting = false
            hid = proxy as BluetoothHidDevice
            if (foreground && enabled) register()
        }
        override fun onServiceDisconnected(profile: Int) {
            output?.close(); output = null
            hid = null
            requesting = false
            registering = false
            registered = false
            connected = false
            host = null
            status = "Служба Bluetooth отключена"
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) refreshDevices()
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED && foreground && enabled) start()
        }
    }
    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    fun resume() { foreground = true; if (enabled) start() }
    fun pause() { releaseInputs(); foreground = false }
    fun activate() { enabled = true; start() }
    fun deactivate() { releaseInputs(); enabled = false }
    fun permissionDenied() { status = "Для управления ПК разреши доступ к устройствам поблизости" }
    fun start(): Unit = safely {
        if (!hasPermission()) { permissionDenied(); return@safely }
        if (adapter == null) { status = "На телефоне нет Bluetooth"; return@safely }
        if (!adapter.isEnabled) { status = "Включи Bluetooth для подключения"; return@safely }
        refreshDevices()
        if (hid != null) register()
        else if (!requesting) {
            status = "Запускаем Bluetooth HID…"
            requesting = adapter.getProfileProxy(context, listener, BluetoothProfile.HID_DEVICE)
            if (!requesting) status = "Этот телефон не поддерживает Bluetooth HID"
        }
    }
    private fun register(): Unit = safely {
        if (registered || registering || !enabled || !foreground) return@safely
        registering = hid?.registerApp(
            BluetoothHidDeviceAppSdpSettings("Air Remote", "Keyboard and trackpad", "Air Remote",
                BluetoothHidDevice.SUBCLASS1_COMBO, PcHidReports.descriptor),
            null, BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
            ), context.mainExecutor, callback,
        ) == true
        if (!registering) status = "Не удалось включить HID. Закрой другие Bluetooth-пульты и повтори"
    }
    fun refreshDevices() = safely {
        if (hasPermission()) devices = adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty()
    }
    fun deviceLabel(device: BluetoothDevice): String = try { device.name ?: device.address } catch (_: SecurityException) { "Компьютер" }
    fun connect(device: BluetoothDevice): Unit = safely {
        if (!registered) { start(); return@safely }
        if (host != null && host != device) { status = "Сначала отключи текущий компьютер"; return@safely }
        if (hid?.connect(device) == true) status = "Подключаемся к ${device.name ?: "компьютеру"}…"
        else status = "Подключение не удалось. Проверь Bluetooth на ПК и повтори"
    }
    fun disconnect() = safely { releaseInputs(); host?.let { hid?.disconnect(it) } }
    fun key(code: Int) {
        if (!connected) return
        if (output?.key(code, modifiers) != true) status = "Bluetooth не успевает отправлять. Переподключи ПК"
        modifiers = 0
    }
    fun toggleModifier(mask: Int) { if (connected) modifiers = modifiers xor mask }
    fun volume(direction: Int) { if (connected) output?.consumer(direction) }
    fun move(x: Int, y: Int, wheel: Int = 0) { if (connected) output?.move(x, y, wheel) }
    fun click(right: Boolean = false) {
        if (!connected) return
        dragging = false
        output?.buttons(if (right) 2 else 1)
        output?.buttons(0)
    }
    fun dragButton(pressed: Boolean) { if (connected) { dragging = pressed; output?.buttons(if (pressed) 1 else 0) } }
    fun releaseInputs() {
        modifiers = 0
        dragging = false
        output?.release()
    }
    override fun onCleared() {
        closed = true
        enabled = false
        foreground = false
        releaseInputs()
        output?.close(); output = null
        context.unregisterReceiver(receiver)
        safely { hid?.unregisterApp(); hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } }
    }
}
