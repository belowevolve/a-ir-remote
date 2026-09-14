package dev.air.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun AppHeader(
    title: String,
    actionIcon: ImageVector? = null,
    actionDescription: String = "Настройки",
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = RemoteLayout.HeaderSize), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (actionIcon != null && onAction != null) {
            IconButton(onClick = onAction, modifier = Modifier.size(RemoteLayout.HeaderActionSize)) {
                Icon(actionIcon, actionDescription, Modifier.size(RemoteLayout.HeaderIconSize),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

private data class ModeItem(val icon: ImageVector, val label: String)

@Composable
internal fun ModeTabs(selected: Int, onSelected: (Int) -> Unit) {
    val items = listOf(
        ModeItem(Icons.Rounded.Tv, "ТВ"),
        ModeItem(Icons.Rounded.Computer, "ПК"),
        ModeItem(Icons.Rounded.Mic, "Микрофон"),
    )
    Row(
        modifier = Modifier.padding(horizontal = RemoteLayout.ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap),
    ) {
        items.forEachIndexed { index, item ->
            Surface(
                onClick = { onSelected(index) },
                modifier = Modifier.weight(if (index == 2) 1.35f else 1f),
                shape = RemoteLayout.HeaderShape,
                color = if (selected == index) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
                contentColor = if (selected == index) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(Modifier.heightIn(min = RemoteLayout.TabHeight), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, item.label, Modifier.size(RemoteLayout.TabIconSize))
                    Spacer(Modifier.width(RemoteLayout.SmallGap))
                    Text(item.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun RemoteDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
) {
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = RemoteLayout.ScreenPadding, vertical = RemoteLayout.DialogInset).widthIn(max = RemoteLayout.DialogMaxWidth).fillMaxWidth(),
            shape = RemoteLayout.DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(RemoteLayout.ScreenPadding), verticalArrangement = Arrangement.spacedBy(RemoteLayout.Gap)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}
