package dev.air.remote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

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
internal fun PcKeyboard(model: PcRemoteModel, russian: Boolean, keyHeight: Dp = KeyboardLayout.KeyHeight, toolbar: @Composable () -> Unit = {}) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, LocalKeyHeight provides keyHeight) {
        Column(Modifier.fillMaxWidth().padding(horizontal = KeyboardLayout.EdgePadding), verticalArrangement = Arrangement.spacedBy(KeyboardLayout.RowGap)) {
            keyboardRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyboardLayout.KeyGap)) {
                    row.forEach { (label, code) ->
                        val index = if (label.length == 1) latin.indexOf(label) else -1
                        PcKey(
                            label = if (russian && (index >= 0)) cyrillic[index].toString() else label,
                            modifier = Modifier.weight(if (label.length > 1 && label !in listOf("Esc", "Del") && !label.startsWith("F")) 1.3f else 1f),
                            enabled = model.connected,
                            selected = (code == -2) && ((model.modifiers and 2) != 0),
                            repeatOnHold = code == 42 || code == 76,
                        ) {
                            if (code == -2) model.toggleModifier(2) else model.key(code)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(KeyboardLayout.KeyGap)) {
                listOf("Ctrl" to 1, "Win" to 8, "Alt" to 4).forEach { (label, mask) ->
                    PcKey(
                        label = label,
                        modifier = Modifier.weight(1f),
                        enabled = model.connected,
                        selected = (model.modifiers and mask) != 0,
                    ) { model.toggleModifier(mask) }
                }
                PcKey("Space", Modifier.weight(2.5f), model.connected) { model.key(44) }
                listOf("Home" to 74, "End" to 77).forEach { (label, code) ->
                    PcKey(label, Modifier.weight(1f), model.connected) { model.key(code) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                PcKey("Win", Modifier.weight(1f), model.connected) { model.windowsKey() }
                Box(Modifier.weight(2f), contentAlignment = Alignment.Center) { toolbar() }
                Column(Modifier.weight(1.65f), verticalArrangement = Arrangement.spacedBy(KeyboardLayout.RowGap)) {
                    PcKey("↑", Modifier.align(Alignment.CenterHorizontally).width(KeyboardLayout.ArrowWidth), model.connected, repeatOnHold = true) { model.key(82) }
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyboardLayout.KeyGap)) {
                        listOf("←" to 80, "↓" to 81, "→" to 79).forEach { (label, code) ->
                            PcKey(label, Modifier.weight(1f), model.connected, repeatOnHold = true) { model.key(code) }
                        }
                    }
                }
            }
        }
    }
}

private val LocalKeyHeight = compositionLocalOf { KeyboardLayout.KeyHeight }

@Composable
private fun PcKey(
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    selected: Boolean = false,
    repeatOnHold: Boolean = false,
    click: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val currentClick by rememberUpdatedState(click)
    var repeating by remember { mutableStateOf(false) }
    LaunchedEffect(pressed, enabled, repeating) {
        if (!pressed || !enabled) {
            repeating = false
        } else if (repeating) {
            while (true) {
                currentClick()
                delay(60)
            }
        }
    }
    Surface(
        modifier = modifier.height(LocalKeyHeight.current).clip(KeyboardLayout.KeyShape)
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = interaction,
                indication = ripple(),
                onClick = { currentClick() },
                onLongClick = if (repeatOnHold) ({ repeating = true }) else null,
            ),
        shape = KeyboardLayout.KeyShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, fontSize = if (label.length >= 3) 11.sp else 14.sp, maxLines = 1)
        }
    }
}
