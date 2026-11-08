package belowevolve.airremote

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

@Composable
internal fun ConnectionGate(
    available: Boolean,
    label: String,
    onConnect: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(if (available) Modifier else Modifier.clearAndSetSemantics {}) { content() }
        if (!available)
            Box(
                Modifier.matchParentSize()
                    .clickable(role = Role.Button, onClick = onConnect)
                    .semantics { contentDescription = label }
            )
    }
}

@Composable
internal fun AppHeader(
    title: String,
    actionIcon: ImageVector? = null,
    actionDescription: String = "Настройки",
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = RemoteLayout.HeaderSize),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier =
                Modifier.weight(1f)
                    .heightIn(min = RemoteLayout.HeaderSize)
                    .then(
                        if (onAction != null)
                            Modifier.clickable(role = Role.Button, onClick = onAction)
                        else Modifier
                    )
                    .wrapContentHeight(Alignment.CenterVertically),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionIcon != null && onAction != null) {
            IconButton(
                onClick = onAction,
                modifier = Modifier.size(RemoteLayout.HeaderActionSize),
            ) {
                Icon(
                    actionIcon,
                    actionDescription,
                    Modifier.size(RemoteLayout.HeaderIconSize),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

internal enum class DeviceKind(val icon: ImageVector, val label: String) {
    TV(Icons.Rounded.Tv, "ТВ"),
    PC(Icons.Rounded.Computer, "ПК"),
    MICROPHONE(Icons.Rounded.Mic, "Микрофон"),
}

@Composable
internal fun DeviceDialogTitle(kind: DeviceKind, name: String) {
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
    ) {
        Icon(kind.icon, kind.label, Modifier.size(RemoteLayout.HeaderIconSize))
        Text(name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ModeTabs(selected: Int, onSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = RemoteLayout.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
    ) {
        DeviceKind.entries.forEachIndexed { index, item ->
            Surface(
                onClick = { onSelected(index) },
                modifier = Modifier.weight(if (index == 2) 1.35f else 1f),
                shape = RemoteLayout.HeaderShape,
                color =
                    if (selected == index) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                contentColor =
                    if (selected == index) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    Modifier.heightIn(min = RemoteLayout.TabHeight),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(item.icon, item.label, Modifier.size(RemoteLayout.TabIconSize))
                    Spacer(Modifier.width(RemoteLayout.SmallGap))
                    Text(item.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun <T : Any> RemoteDialog(
    screen: T,
    onDismissRequest: () -> Unit,
    title: @Composable (T) -> Unit,
    text: @Composable (T) -> Unit,
    confirmButton: @Composable (T) -> Unit,
    dismissButton: (@Composable (T) -> Unit)? = null,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                if (it.attributes.windowAnimations != R.style.Animation_AirRemoteDialog)
                    it.setWindowAnimations(R.style.Animation_AirRemoteDialog)
                if (kotlin.math.abs(it.attributes.dimAmount - 0.32f) > 0.001f)
                    it.setDimAmount(0.32f)
            }
        }
        CaptureDialogVolumeKeys()
        Surface(
            modifier =
                Modifier.padding(
                        horizontal = RemoteLayout.ScreenPadding,
                        vertical = RemoteLayout.DialogInset,
                    )
                    .widthIn(max = RemoteLayout.DialogMaxWidth)
                    .fillMaxWidth(),
            shape = RemoteLayout.DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                Modifier.padding(RemoteLayout.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
            ) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title(screen) }
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                ) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text(screen) }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke(screen)
                    Spacer(Modifier.weight(1f))
                    confirmButton(screen)
                }
            }
        }
    }
}
