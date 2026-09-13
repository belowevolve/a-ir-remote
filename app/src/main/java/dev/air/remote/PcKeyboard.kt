package dev.air.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val keyboardRows = listOf(
    listOf("Esc" to 41) + (1..12).map { "F$it" to (57 + it) } + listOf("Del" to 76),
    listOf("`" to 53) + "1234567890".mapIndexed { index, c -> c.toString() to (30 + index) } + listOf("-" to 45, "=" to 46, "⌫" to 42),
    listOf("Tab" to 43) + "qwertyuiop".map { it.toString() to (4 + (it - 'a')) } + listOf("[" to 47, "]" to 48, "\\" to 49),
    listOf("Caps" to 57) + "asdfghjkl".map { it.toString() to (4 + (it - 'a')) } + listOf(";" to 51, "'" to 52, "Enter" to 40),
    listOf("Shift" to -2) + "zxcvbnm".map { it.toString() to (4 + (it - 'a')) } + listOf("," to 54, "." to 55, "/" to 56, "Shift" to -2),
)
private const val latin = "`qwertyuiop[]asdfghjkl;'zxcvbnm,."
private const val cyrillic = "ёйцукенгшщзхъфывапролджэячсмитьбю"

@Composable
internal fun PcKeyboard(model: PcRemoteModel, russian: Boolean) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            keyboardRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    row.forEach { (label, code) ->
                        val index = if (label.length == 1) latin.indexOf(label) else -1
                        PcKey(if (russian && index >= 0) cyrillic[index].toString() else label,
                            Modifier.weight(if (label.length > 1) 1.4f else 1f), model.connected, code == -2 && model.modifiers and 2 != 0) {
                            if (code == -2) model.toggleModifier(2) else model.key(code)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf("Ctrl" to 1, "Win" to 8, "Alt" to 4).forEach { (label, mask) ->
                    PcKey(label, Modifier.weight(1f), model.connected, model.modifiers and mask != 0) { model.toggleModifier(mask) }
                }
                PcKey("Space", Modifier.weight(2.5f), model.connected) { model.key(44) }
                listOf("Home" to 74, "End" to 77).forEach { (label, code) ->
                    PcKey(label, Modifier.weight(1f), model.connected) { model.key(code) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                PcKey("Win", Modifier.width(64.dp), model.connected) { model.windowsKey() }
                Spacer(Modifier.weight(1f))
                Column(Modifier.width(132.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PcKey("↑", Modifier.align(Alignment.CenterHorizontally).width(42.dp), model.connected) { model.key(82) }
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        listOf("←" to 80, "↓" to 81, "→" to 79).forEach { (label, code) ->
                            PcKey(label, Modifier.weight(1f), model.connected) { model.key(code) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PcKey(label: String, modifier: Modifier, enabled: Boolean, selected: Boolean = false, click: () -> Unit) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.height(42.dp), shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = if (label.length >= 3) 10.sp else 13.sp, maxLines = 1) } }
}
