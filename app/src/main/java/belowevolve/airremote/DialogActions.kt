package belowevolve.airremote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

@Composable
internal fun DialogActionGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = DialogActionLayout.Shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun <T> DeviceConnectionList(
    devices: List<T>,
    loading: Boolean,
    label: (T) -> String,
    connected: (T) -> Boolean,
    connecting: (T) -> Boolean,
    enabled: Boolean = true,
    onClick: (T) -> Unit,
) {
    DialogActionGroup {
        if (loading && devices.isEmpty())
            Row(
                Modifier.fillMaxWidth().padding(RemoteLayout.ScreenPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
            ) {
                CircularProgressIndicator(Modifier.size(RemoteLayout.InlineIconSize), strokeWidth = 2.dp)
                Text("Загрузка устройств…", style = MaterialTheme.typography.labelLarge)
            }
        devices.forEach { device ->
            val pending = connecting(device)
            DialogActionRow(
                label(device),
                selected = connected(device) && !pending,
                loading = pending,
                enabled = enabled,
                onClick = { onClick(device) },
            )
        }
        if (devices.isEmpty() && !loading)
            Text("Устройства не найдены", Modifier.padding(RemoteLayout.ScreenPadding))
    }
}

@Composable
internal fun DialogActionRow(
    label: String,
    selected: Boolean = false,
    loading: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val active = enabled && !loading
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val color =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 56.dp)
            .clipToBounds()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
            )
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                if (loading) stateDescription = "Подключение"
            }
            .padding(horizontal = RemoteLayout.ScreenPadding, vertical = RemoteLayout.Gap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelLarge,
            color = color.copy(alpha = if (active || loading) 1f else 0.38f),
        )
        if (loading)
            CircularProgressIndicator(
                Modifier.size(RemoteLayout.InlineIconSize),
                strokeWidth = 2.dp,
            )
        else
            Icon(
                if (selected) Icons.Rounded.Check else Icons.Rounded.ChevronRight,
                null,
                Modifier.size(RemoteLayout.InlineIconSize).graphicsLayer {
                    scaleX = if (rtl && !selected) -1f else 1f
                },
                tint = color.copy(alpha = if (active) 0.7f else 0.38f),
            )
    }
}

@Composable
internal fun DialogToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    belowevolve.shared.compose.AirToggleRow(label, checked, onChecked)
}

@Composable
internal fun DialogSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = RemoteLayout.ScreenPadding, vertical = RemoteLayout.SmallGap)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RemoteLayout.Gap),
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text(
                "%.1f×".format(value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value, onValueChange, valueRange = valueRange, steps = steps)
    }
}
