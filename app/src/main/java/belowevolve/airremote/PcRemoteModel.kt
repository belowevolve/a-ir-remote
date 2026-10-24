package belowevolve.airremote

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
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private var pendingHost: BluetoothDevice? = null
    private var disconnectingHost: BluetoothDevice? = null
    private var output: PcHidOutput? = null
    private var reconnectJob: Job? = null
    private var keepingConnection = false
    private var keyboardLeds = 0
    var capsLock by mutableStateOf(false)
        private set
    var registered by mutableStateOf(value = false)
        private set
    var connected by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf("Компьютер")
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
    var russianLayout by mutableStateOf(prefs.getBoolean("russian_layout", false))
        private set

    fun toggleKeyboardLayout() {
        russianLayout = !russianLayout
        prefs.edit { putBoolean("russian_layout", russianLayout) }
    }

    private val selection = PcHostSelection(prefs.getString("host", null), prefs.getBoolean("auto_connect", true))
    var sensitivity by mutableFloatStateOf(PcPointerSensitivity.normalize(prefs.getFloat("sensitivity", 1.6f)))
        private set

    fun updateSensitivity(value: Float) {
        sensitivity = PcPointerSensitivity.normalize(value)
        prefs.edit { putFloat("sensitivity", sensitivity) }
    }

    fun hasPermission() = (Build.VERSION.SDK_INT < 31) ||
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
                stopKeepingConnection()
                output?.close(); output = null
                host = null
                disconnectingHost = null
                connected = false
                modifiers = 0
                dragging = false
                status = "HID отключён. Нажми «Подключить», чтобы повторить"
                scheduleReconnect()
            } else if (closed || (!foreground && !keepingConnection) || (!enabled && !connected)) {
                safely { hid?.unregisterApp() }
            } else {
                status = "Готов к сопряжению с Windows"
                refreshDevices()
                if (selection.allowed) {
                    val target = pendingHost ?: devices.firstOrNull { it.address == selection.address }
                        ?: pluggedDevice?.takeIf { selection.accepts(it.address) }
                    target?.let(::connect)
                } else status = "Отключено. Выбери компьютер для подключения"
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) = safely {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (!selection.accepts(device.address) || !registered || disconnectingHost == device) {
                        hid?.disconnect(device)
                        return@safely
                    }
                    if (host == device && connected) return@safely
                    keepConnection()
                    pendingHost = null
                    selection.select(device.address)
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
                    deviceName = device.name ?: "Компьютер"
                    connected = true
                    reconnectJob?.cancel()
                    keyboardLeds = 0
                    capsLock = false
                    status = "${device.name ?: "Компьютер"} · подключён"
                    prefs.edit { putString("host", device.address) }
                    releaseInputs()
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    if (!selection.accepts(device.address)) hid?.disconnect(device)
                    else {
                        if (host == null) host = device
                        status = "Подключаемся к ${device.name ?: "компьютеру"}…"
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (disconnectingHost == device) disconnectingHost = null
                    if (host == device) {
                        output?.close(); output = null
                        host = null
                        connected = false
                        modifiers = 0
                        dragging = false
                        status = "Соединение закрыто. Выбери компьютер для подключения"
                        val next = pendingHost
                        pendingHost = null
                        if (next != null && selection.accepts(next.address)) connect(next)
                        else {
                            stopKeepingConnection()
                            scheduleReconnect()
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) = safely {
            val report = when (type) {
                BluetoothHidDevice.REPORT_TYPE_INPUT -> when (id.toInt()) {
                    PcHidReports.KEYBOARD -> PcHidReports.keyboard()
                    PcHidReports.MOUSE -> PcHidReports.mouse(if (dragging) 1 else 0)
                    PcHidReports.CONSUMER -> PcHidReports.consumer()
                    else -> null
                }
                BluetoothHidDevice.REPORT_TYPE_OUTPUT -> when (id.toInt()) {
                    PcHidReports.KEYBOARD -> byteArrayOf(keyboardLeds.toByte())
                    else -> null
                }
                else -> null
            }
            if (report != null) hid?.replyReport(device, type, id, if (bufferSize > 0) report.take(bufferSize).toByteArray() else report)
            else hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
        }

        override fun onInterruptData(device: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (reportId.toInt() == PcHidReports.KEYBOARD) updateKeyboardLeds(device, data)
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) = safely {
            hid?.reportError(
                device,
                if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id.toInt() == PcHidReports.KEYBOARD) {
                    updateKeyboardLeds(device, data)
                    BluetoothHidDevice.ERROR_RSP_SUCCESS
                } else {
                    BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ
                },
            )
        }
    }

    private fun updateKeyboardLeds(device: BluetoothDevice, data: ByteArray) {
        if (device != host || data.isEmpty()) return
        keyboardLeds = data[0].toInt() and 31
        capsLock = keyboardLeds and 2 != 0
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true || !foreground || !enabled || !selection.allowed || connected) return
        reconnectJob = viewModelScope.launch {
            for (wait in listOf(500L, 1000L, 2000L, 4000L, 8000L)) {
                delay(wait)
                if (!foreground || !enabled || !selection.allowed || connected || closed) break
                safely {
                    if (!hasPermission() || adapter?.isEnabled != true) return@safely
                    if (!registered) start()
                    else if (disconnectingHost == null) {
                        val target = devices.firstOrNull { it.address == selection.address }
                        if (target != null && hid?.getConnectionState(target) == BluetoothProfile.STATE_DISCONNECTED) {
                            host = null
                            connect(target)
                        }
                    }
                }
            }
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
            stopKeepingConnection()
            output?.close(); output = null
            hid = null
            requesting = false
            registering = false
            registered = false
            connected = false
            host = null
            disconnectingHost = null
            modifiers = 0
            dragging = false
            status = "Служба Bluetooth отключена"
            scheduleReconnect()
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

    fun resume() { foreground = true; if (enabled) { start(); scheduleReconnect() } }
    fun pause() { releaseInputs(); foreground = false; reconnectJob?.cancel() }
    fun activate() { enabled = true; start(); scheduleReconnect() }
    fun deactivate() { releaseInputs(); enabled = false; reconnectJob?.cancel() }
    fun permissionDenied() { status = "Для управления ПК разреши доступ к устройствам поблизости" }

    private fun keepConnection() {
        // Start while the Activity is visible, before Android lowers the UID's importance.
        if (keepingConnection || !foreground) return
        context.startForegroundService(Intent(context, PcConnectionService::class.java))
        keepingConnection = true
    }

    private fun stopKeepingConnection() {
        if (!keepingConnection) return
        context.stopService(Intent(context, PcConnectionService::class.java))
        keepingConnection = false
    }
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
            BluetoothHidDeviceAppSdpSettings(context.getString(R.string.app_name), "Keyboard and trackpad", "belowevolve",
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
        if (!hasPermission()) { permissionDenied(); return@safely }
        selection.select(device.address)
        prefs.edit { putString("host", device.address); putBoolean("auto_connect", true) }
        pendingHost = device
        if (!registered) { start(); return@safely }
        if (disconnectingHost != null) {
            status = "Переключаемся на ${deviceLabel(device)}…"
            return@safely
        }
        val current = host
        if (current == device && connected) { pendingHost = null; return@safely }
        if (current != null && current != device) {
            releaseInputs()
            if (hid?.disconnect(current) == true) {
                disconnectingHost = current
                connected = false
                status = "Переключаемся на ${deviceLabel(device)}…"
            } else {
                pendingHost = null
                status = "Не удалось отключить текущий ПК. Повтори выбор компьютера"
            }
            return@safely
        }
        pendingHost = null
        keepConnection()
        if (hid?.connect(device) == true) {
            host = device // Also track an in-flight connection so it can be cancelled.
            status = "Подключаемся к ${deviceLabel(device)}…"
        } else {
            if (!connected) stopKeepingConnection()
            status = "Подключение не удалось. Проверь Bluetooth на ПК и повтори"
        }
    }
    fun disconnect() = safely {
        reconnectJob?.cancel()
        selection.disconnect()
        pendingHost = null
        prefs.edit { putBoolean("auto_connect", false) }
        releaseInputs()
        val current = host
        if (current != null && hid?.disconnect(current) != true) {
            status = "Не удалось отключить ПК. Повтори отключение"
        } else {
            disconnectingHost = current
            connected = false
            stopKeepingConnection()
            status = "Отключено. Выбери компьютер для подключения"
        }
    }
    fun preparePairing() {
        if (host != null) disconnect()
        selection.pair()
        prefs.edit { remove("host"); putBoolean("auto_connect", true) }
    }
    private fun sendKey(code: Int, modifiers: Int): Boolean {
        if (!connected) return false
        val queued = output?.key(code, modifiers) == true
        if (!queued) status = "Bluetooth не успевает отправлять. Переподключи ПК"
        return queued
    }

    fun key(code: Int) {
        sendKey(code, modifiers)
        modifiers = 0
    }

    fun windowsKey() {
        sendKey(0, 8)
        modifiers = 0
    }

    // Send Win + Space as one chord, independent of sticky keyboard modifiers.
    fun switchLanguage() {
        if (sendKey(44, 8)) toggleKeyboardLayout()
    }

    fun toggleModifier(mask: Int) { if (connected) modifiers = modifiers xor mask }
    fun volume(direction: Int) {
        if (connected && output?.consumer(direction) != true) status = "Bluetooth не успевает отправлять. Переподключи ПК"
    }
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
        stopKeepingConnection()
    }
}
