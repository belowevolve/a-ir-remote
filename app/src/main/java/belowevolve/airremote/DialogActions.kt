package belowevolve.airremote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected

@Composable
internal fun DialogActionGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = DialogActionLayout.Shape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
internal fun DialogActionRow(label: String, selected: Boolean = false, loading: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().heightIn(min = RemoteLayout.HeaderSize).semantics { this.selected = selected },
        shape = DialogActionLayout.Shape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary,
        ),
        contentPadding = PaddingValues(horizontal = RemoteLayout.Gap, vertical = RemoteLayout.SmallGap),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RemoteLayout.SmallGap)) {
            Text(label, Modifier.weight(1f))
            if (loading) CircularProgressIndicator(Modifier.size(RemoteLayout.InlineIconSize), strokeWidth = 2.dp)
        }
    }
}
