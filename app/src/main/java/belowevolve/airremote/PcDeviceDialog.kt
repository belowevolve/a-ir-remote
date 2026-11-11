package belowevolve.airremote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext

internal fun PcRemoteModel.deviceDialogPage(kind: DeviceKind): PcDialogPage =
    pcDevicePage(kind, connected, companionConnected)

internal fun pcDevicePage(kind: DeviceKind, bluetooth: Boolean, companion: Boolean): PcDialogPage =
    if (if (kind == DeviceKind.MICROPHONE) companion else bluetooth) PcDialogPage.SETTINGS
    else pcConnectionPage(bluetooth)

internal fun pcConnectionPage(bluetooth: Boolean): PcDialogPage =
    if (bluetooth) PcDialogPage.COMPANION else PcDialogPage.BLUETOOTH

internal fun PcRemoteModel.connectionPage() = pcConnectionPage(connected)

/** Each connection step opens above its retained parent. */
@Composable
internal fun PcDeviceDialog(
    model: PcRemoteModel,
    kind: DeviceKind = DeviceKind.PC,
    initialPage: PcDialogPage,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val saver = remember {
        listSaver<PcDialogNavigation, String>(
            save = { it.pages.map(PcDialogPage::name) },
            restore = { PcDialogNavigation(it.map(PcDialogPage::valueOf)) },
        )
    }
    var navigation by
        rememberSaveable(stateSaver = saver) { mutableStateOf(PcDialogNavigation(initialPage)) }
    val page = navigation.page
    var waitingForBluetooth by rememberSaveable { mutableStateOf(false) }
    var scanningCompanion by rememberSaveable { mutableStateOf(!model.companionConnected) }
    var code by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        if (!closing) {
            closing = true
            onDismiss()
        }
    }
    fun open(next: PcDialogPage) {
        if (next == PcDialogPage.COMPANION) scanningCompanion = !model.companionConnected
        navigation = navigation.open(next)
    }
    fun back() {
        if (navigation.canGoBack) navigation = navigation.back() else close()
    }
    fun pair(value: String) {
        try {
            model.pairCompanion(value)
            code = ""
            error = ""
            close()
        } catch (e: IllegalArgumentException) {
            code = value
            error = e.message.orEmpty()
            open(PcDialogPage.COMPANION_CODE)
        }
    }
    val bluetooth =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            model.start()
        }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (model.hasPermission()) model.start() else model.permissionDenied()
        }
    fun launchBluetooth(intent: Intent) {
        try {
            bluetooth.launch(intent)
            error = ""
        } catch (_: android.content.ActivityNotFoundException) {
            error = "Открой настройки Bluetooth телефона"
        } catch (_: SecurityException) {
            model.permissionDenied()
        }
    }
    LaunchedEffect(page) {
        if (page == PcDialogPage.BLUETOOTH && model.hasPermission()) {
            model.refreshDevices()
            model.start()
        }
    }
    LaunchedEffect(model.connected, page) {
        if (waitingForBluetooth && model.connected && page == PcDialogPage.BLUETOOTH) {
            waitingForBluetooth = false
            close()
        }
    }
    LaunchedEffect(model.companionConnected, page) {
        if (scanningCompanion && model.companionConnected && PcDialogPage.COMPANION in navigation.pages) close()
    }
    navigation.pages.forEachIndexed { index, page ->
        key(index, page) {
            val topmost = index == navigation.pages.lastIndex
            RemoteDialog(
                screen = page,
                onDismissRequest = { if (topmost) close() },
                title = { page ->
                    val scanning = page == PcDialogPage.COMPANION && scanningCompanion
                    DeviceDialogTitle(
                        kind,
                        when {
                            page == PcDialogPage.BLUETOOTH -> "Подключить ПК"
                            page == PcDialogPage.COMPANION_CODE -> "Ввести код"
                            scanning -> "Сканировать QR"
                            kind == DeviceKind.MICROPHONE && !model.companionConnected -> "Подключить ПК"
                            else -> model.pcName
                        },
                    )
                },
                text = { page ->
                    val manual = page == PcDialogPage.COMPANION_CODE
                    val scanning = page == PcDialogPage.COMPANION && scanningCompanion
                    when (page) {
                        PcDialogPage.SETTINGS ->
                            DialogActionGroup {
                                if (kind == DeviceKind.MICROPHONE)
                                    Column {
                                        DialogToggleRow("Показывать волну", model.waveformEnabled, onChecked = model::updateWaveform)
                                        DialogToggleRow("Анимация микрофона", model.iconMeterEnabled, onChecked = model::updateIconMeter)
                                        NotificationSettingsRow("microphone", "Микрофон для ПК")
                                    }
                                else {
                                    DialogToggleRow("Автоклавиатура", model.autoKeyboard) { enabled ->
                                        if (enabled && !model.nativeKeyboardSupported)
                                            open(PcDialogPage.COMPANION)
                                        else model.updateAutoKeyboard(enabled)
                                    }
                                    DialogSliderRow(
                                        "Чувствительность",
                                        model.sensitivity,
                                        model::updateSensitivity,
                                        PcPointerSensitivity.Range,
                                        36,
                                    )
                                    NotificationSettingsRow("pc_connection", "Подключение к ПК")
                                }
                                if (!model.connected)
                                    DialogActionRow("Подключить Bluetooth") { open(PcDialogPage.BLUETOOTH) }
                                if (
                                    !model.companionConnected &&
                                        (!model.hasCompanion || model.companionError != null)
                                )
                                    DialogActionRow("Подключить компаньон") { open(PcDialogPage.COMPANION) }
                            }
                        PcDialogPage.BLUETOOTH ->
                            Column(verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
                                DialogActionGroup {
                                    DialogActionRow(
                                        if (model.companionConnected) "Компаньон подключён"
                                        else "Подключить компаньон",
                                        selected = model.companionConnected,
                                        enabled = !model.companionConnected,
                                        loading = model.companionConnecting && !model.companionConnected,
                                    ) {
                                        open(PcDialogPage.COMPANION)
                                    }
                                }
                                Text(
                                    "Bluetooth · клавиатура и мышь",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (!model.hasPermission())
                                    TextButton(
                                        onClick = {
                                            permissions.launch(
                                                arrayOf(
                                                    Manifest.permission.BLUETOOTH_CONNECT,
                                                    Manifest.permission.BLUETOOTH_ADVERTISE,
                                                )
                                            )
                                        }
                                    ) {
                                        Text("Разрешить Bluetooth")
                                    }
                                if (model.connectionError.isNotBlank())
                                    Text(model.connectionError, color = MaterialTheme.colorScheme.error)
                                if (model.bluetoothState == BluetoothAdapter.STATE_OFF)
                                    TextButton(
                                        onClick = {
                                            launchBluetooth(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                        }
                                    ) {
                                        Text("Включить Bluetooth")
                                    }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
                                ) {
                                    FilledTonalButton(
                                        modifier = Modifier.weight(1.6f),
                                        contentPadding = PaddingValues(horizontal = RemoteLayout.Gap),
                                        enabled = model.registered,
                                        onClick = {
                                            if (
                                                Build.VERSION.SDK_INT >= 31 &&
                                                    context.checkSelfPermission(
                                                        Manifest.permission.BLUETOOTH_ADVERTISE
                                                    ) !=
                                                        android.content.pm.PackageManager.PERMISSION_GRANTED
                                            )
                                                permissions.launch(
                                                    arrayOf(
                                                        Manifest.permission.BLUETOOTH_CONNECT,
                                                        Manifest.permission.BLUETOOTH_ADVERTISE,
                                                    )
                                                )
                                            else {
                                                model.preparePairing()
                                                waitingForBluetooth = true
                                                launchBluetooth(
                                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                                                        .putExtra(
                                                            BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                                            120,
                                                        )
                                                )
                                            }
                                        },
                                    ) {
                                        Text("Видимость · 2 мин")
                                    }
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = RemoteLayout.Gap),
                                        onClick = model::start,
                                    ) {
                                        Text("Обновить")
                                    }
                                }
                                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                                DeviceConnectionList(
                                    devices = model.devices,
                                    loading = model.loadingDevices,
                                    label = model::deviceLabel,
                                    connected = { model.connected && model.isSelectedDevice(it) },
                                    connecting = model::isConnectingDevice,
                                    enabled = model.registered,
                                ) { device ->
                                    waitingForBluetooth = true
                                    model.connect(device)
                                }
                            }
                        PcDialogPage.COMPANION,
                        PcDialogPage.COMPANION_CODE ->
                            when {
                                manual -> {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)
                                    ) {
                                        OutlinedTextField(
                                            code,
                                            {
                                                code = it
                                                error = ""
                                            },
                                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                                            singleLine = true,
                                            label = { Text("Код") },
                                            isError = error.isNotEmpty(),
                                        )
                                        if (error.isNotEmpty())
                                            Text(error, color = MaterialTheme.colorScheme.error)
                                    }
                                    LaunchedEffect(Unit) { focus.requestFocus() }
                                }
                                scanning ->
                                    CompanionScannerContent(
                                        active = topmost && !model.companionConnected,
                                        onCode = { if (topmost) pair(it) }
                                    )
                                else ->
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)
                                    ) {
                                        model.companionError?.let {
                                            Text(it, color = MaterialTheme.colorScheme.error)
                                        }
                                        if (!model.companionConnected)
                                            DialogActionGroup {
                                                DialogActionRow(
                                                    "Подключиться",
                                                    loading = model.companionConnecting,
                                                    onClick = model::reconnectCompanion,
                                                )
                                            }
                                        else if (
                                            !model.nativeKeyboardSupported || !model.clipboardSupported
                                        )
                                            Text("Обнови Air Companion на ПК")
                                        else Text("Компаньон подключён")
                                    }
                            }
                    }
                },
                dismissButton = { page ->
                    when {
                        page == PcDialogPage.SETTINGS && (model.hasSelectedDevice || model.hasCompanion) ->
                            TextButton(
                                onClick = {
                                    model.forgetCurrentPc()
                                    close()
                                },
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    ),
                            ) {
                                Text("Забыть ПК")
                            }
                        page == PcDialogPage.COMPANION_CODE ->
                            TextButton(onClick = ::back) { Text("Сканировать QR") }
                        page != PcDialogPage.SETTINGS && index > 0 ->
                            TextButton(onClick = ::back) { Text("Назад") }
                        page == PcDialogPage.COMPANION -> TextButton(onClick = ::close) { Text("Отмена") }
                    }
                },
                confirmButton = { page ->
                    val scanning = page == PcDialogPage.COMPANION && scanningCompanion
                    when {
                        page == PcDialogPage.COMPANION_CODE ->
                            TextButton(onClick = { pair(code) }, enabled = code.isNotBlank()) {
                                Text("Подключить")
                            }
                        scanning ->
                            TextButton(onClick = { open(PcDialogPage.COMPANION_CODE) }) {
                                Text("Ввести код")
                            }
                        else -> TextButton(onClick = ::close) {
                            Text(if (page == PcDialogPage.BLUETOOTH) "Закрыть" else "Готово")
                        }
                    }
                },
            )
        }
    }
}
