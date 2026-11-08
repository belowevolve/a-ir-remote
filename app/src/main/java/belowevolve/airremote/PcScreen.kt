package belowevolve.airremote

import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import belowevolve.shared.design.RemoteIcons

@Composable
internal fun PcScreen(model: PcRemoteModel, landscape: Boolean = false) {
    var deviceDialog by rememberSaveable { mutableStateOf<PcDialogPage?>(null) }
    var keyboardVisible by rememberSaveable { mutableStateOf(true) }
    var numpad by rememberSaveable { mutableStateOf(false) }
    val russian = model.russianLayout
    var androidKeyboardVisible by remember { mutableStateOf(false) }
    LaunchedEffect(model.keyboardFocusRequest) {
        if (model.consumeKeyboardFocus() && deviceDialog == null) androidKeyboardVisible = true
    }
    LaunchedEffect(model.nativeKeyboardSupported) {
        if (!model.nativeKeyboardSupported && androidKeyboardVisible) {
            androidKeyboardVisible = false
        }
    }
    fun keyboard() {
        if (model.nativeKeyboardSupported) androidKeyboardVisible = true
        else deviceDialog = model.connectionPage()
    }
    val view = LocalView.current
    LaunchedEffect(model.clipboardResult) {
        model.clipboardResult?.let { result ->
            Toast.makeText(view.context, result, Toast.LENGTH_SHORT).show()
            model.clearClipboardResult()
        }
    }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previous
            model.releaseInputs()
        }
    }
    val trackpadVisible = !landscape || !keyboardVisible
    ConnectionGate(model.connected || model.companionConnected, "Подключить ПК", { deviceDialog = model.connectionPage() }) {
        PcPointerSurface(model, trackpadVisible, onConnect = { deviceDialog = model.connectionPage() }) { trackpadArea ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val keyHeight =
                    if (landscape) {
                        ((maxHeight -
                                KeyboardLayout.ToolbarSize -
                                KeyboardLayout.RowGap * (KeyboardLayout.ROW_COUNT - 1)) /
                                KeyboardLayout.ROW_COUNT)
                            .coerceAtLeast(0.dp)
                    } else KeyboardLayout.keyHeight(maxHeight)
                val canPaste = model.companionConnected && model.clipboardSupported
                val toolbarSize = minOf(KeyboardLayout.ToolbarSize, keyHeight)
                val languageSwitch: @Composable () -> Unit = {
                    Box(
                        Modifier.size(toolbarSize)
                            .clip(CircleShape)
                            .combinedClickable(
                                role = Role.Button,
                                onClickLabel = "Сменить язык ПК и подписи клавиш",
                                onClick = { if (model.connected) model.switchLanguage() else deviceDialog = model.connectionPage() },
                                onDoubleClick = { if (model.connected) model.toggleKeyboardLayout() else deviceDialog = model.connectionPage() },
                            )
                            .semantics {
                                customActions =
                                    listOf(
                                        CustomAccessibilityAction("Сменить только подписи клавиш") {
                                            model.toggleKeyboardLayout()
                                            true
                                        }
                                    )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Language,
                            if (russian) "Язык: русский" else "Язык: английский",
                        )
                    }
                }
                val toolbar: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                        Column(
                            Modifier.width(KeyboardLayout.ToolbarSize * 4),
                            verticalArrangement = Arrangement.spacedBy(KeyboardLayout.RowGap),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(KeyboardLayout.KeyGap),
                            ) {
                                listOf(false, true).forEach { right ->
                                    Surface(
                                        onClick = {
                                            if (model.connected) model.click(right)
                                            else deviceDialog = model.connectionPage()
                                        },
                                        modifier = Modifier.weight(1f).height(keyHeight),
                                        shape = KeyboardLayout.KeyShape,
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                painterResource(
                                                    if (right) R.drawable.ic_mouse_right
                                                    else R.drawable.ic_mouse_left
                                                ),
                                                if (right) "Правая кнопка мыши"
                                                else "Левая кнопка мыши",
                                                Modifier.size(RemoteLayout.ActionIconSize),
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().height(keyHeight),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.width(KeyboardLayout.ToolbarSize).height(toolbarSize),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IconButton(
                                        onClick = {
                                            if (canPaste) model.pasteClipboard()
                                            else deviceDialog = model.connectionPage()
                                        },
                                        enabled = !model.clipboardBusy,
                                        modifier = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(Icons.Rounded.ContentPaste, "Вставить на ПК")
                                    }
                                }
                                IconButton(
                                    onClick = ::keyboard,
                                    modifier =
                                        Modifier.width(KeyboardLayout.ToolbarSize).height(toolbarSize),
                                ) {
                                    Icon(Icons.Rounded.Keyboard, "Клавиатура Android")
                                }
                                IconToggleButton(
                                    checked = numpad,
                                    onCheckedChange = {
                                        numpad = it
                                        keyboardVisible = true
                                    },
                                    modifier =
                                        Modifier.width(KeyboardLayout.ToolbarSize).height(toolbarSize),
                                ) {
                                    Icon(
                                        Icons.Rounded.Dialpad,
                                        if (numpad) "Показать буквенную клавиатуру"
                                        else "Показать нумпад и дополнительные клавиши",
                                    )
                                }
                                IconButton(
                                    onClick = { keyboardVisible = !keyboardVisible },
                                    modifier =
                                        Modifier.width(KeyboardLayout.ToolbarSize).height(toolbarSize),
                                ) {
                                    if (keyboardVisible)
                                        Icon(Icons.Rounded.KeyboardHide, "Скрыть клавиатуру")
                                    else
                                        Icon(
                                            painterResource(R.drawable.ic_keyboard_show),
                                            "Показать клавиатуру",
                                        )
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    if (landscape)
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                            Row(
                                Modifier.fillMaxWidth()
                                    .height(KeyboardLayout.ToolbarSize)
                                    .padding(horizontal = KeyboardLayout.EdgePadding),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = {
                                        deviceDialog = model.deviceDialogPage(DeviceKind.PC)
                                    },
                                    modifier = Modifier.size(KeyboardLayout.ToolbarSize),
                                ) {
                                    Icon(
                                        if (model.connected)
                                            ImageVector.vectorResource(RemoteIcons.Tune)
                                        else Icons.Rounded.Devices,
                                        if (model.connected)
                                            "Настройки ПК"
                                        else "Подключить ПК",
                                        Modifier.size(RemoteLayout.HeaderIconSize),
                                    )
                                }
                            }
                        }
                    if (!landscape)
                        Box(Modifier.padding(horizontal = RemoteLayout.ScreenPadding)) {
                            AppHeader(
                                if (model.connected) model.deviceName
                                else "Подключить ПК",
                                onAction = {
                                    deviceDialog = model.deviceDialogPage(DeviceKind.PC)
                                },
                                actionIcon =
                                    if (model.connected)
                                        ImageVector.vectorResource(RemoteIcons.Tune)
                                    else Icons.Rounded.Devices,
                                actionDescription =
                                    if (model.connected) "Настройки ПК"
                                    else "Подключить ПК",
                            )
                        }
                    if (!landscape) Spacer(Modifier.height(RemoteLayout.SmallGap))
                    if (trackpadVisible) {
                        Box(Modifier.padding(horizontal = KeyboardLayout.EdgePadding).weight(1f)) {
                            Trackpad(trackpadArea.fillMaxSize())
                        }
                        Spacer(Modifier.height(KeyboardLayout.RowGap))
                    }
                    if (keyboardVisible) {
                        PcKeyboard(
                            model,
                            russian,
                            onConnect = { deviceDialog = model.connectionPage() },
                            keyHeight,
                            numpad,
                            landscape = landscape,
                            toolbar = toolbar,
                            languageSwitch = languageSwitch,
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = KeyboardLayout.EdgePadding),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.TopStart) {
                                Box(
                                    (if (landscape) Modifier.width(56.dp) else Modifier.fillMaxWidth())
                                        .height(keyHeight),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    languageSwitch()
                                }
                            }
                            toolbar()
                            Spacer(Modifier.weight(if (landscape) 1f else 1.65f))
                        }
                    }
                }
            }
        }
    }
    deviceDialog?.let { page ->
        PcDeviceDialog(model, initialPage = page, onDismiss = { deviceDialog = null })
    }
    if (androidKeyboardVisible && model.nativeKeyboardSupported)
        PcTextEntry(model) { androidKeyboardVisible = false }
}
