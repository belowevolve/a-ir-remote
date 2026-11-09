package belowevolve.shared.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import belowevolve.shared.design.RemoteShapes
import belowevolve.shared.design.RemoteSpacing

/** One button surface for touch and D-pad. Focus has an explicit Material color role. */
@Composable
fun AirButton(
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  selected: Boolean = false,
  shape: Shape = RoundedCornerShape(RemoteShapes.Medium.dp),
  color: Color = MaterialTheme.colorScheme.surfaceContainer,
  contentColor: Color = MaterialTheme.colorScheme.onSurface,
  focusedColor: Color = MaterialTheme.colorScheme.primary,
  focusedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
  onFocus: (Boolean) -> Unit = {},
  role: Role = Role.Button,
  contentPadding: PaddingValues =
    PaddingValues(horizontal = RemoteSpacing.Large.dp, vertical = RemoteSpacing.Medium.dp),
  content: @Composable RowScope.() -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  var focused by remember { mutableStateOf(false) }
  val emphasis =
    animateFloatAsState(
      if (focused || selected) 1f else 0f,
      animationSpec = tween(100, easing = FastOutSlowInEasing),
      label = "action emphasis",
    )
  CompositionLocalProvider(
    LocalContentColor provides if (focused || selected) focusedContentColor else contentColor
  ) {
    Row(
      modifier
        .graphicsLayer()
        .drawWithCache {
          val outline = shape.createOutline(size, layoutDirection, this)
          onDrawBehind {
            drawOutline(outline, color)
            drawOutline(outline, focusedColor, alpha = emphasis.value)
          }
        }
        .onFocusChanged {
          focused = it.isFocused
          onFocus(it.isFocused)
        }
        .clickable(
          interactionSource = interaction,
          indication = null,
          enabled = enabled,
          role = role,
          onClick = onClick,
        )
        .padding(contentPadding),
      verticalAlignment = Alignment.CenterVertically,
      content = content,
    )
  }
}

@Composable
fun AirToggleRow(
  label: String,
  checked: Boolean,
  onChecked: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Row(
    modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clipToBounds()
      .toggleable(
        value = checked,
        enabled = enabled,
        role = Role.Switch,
        onValueChange = onChecked,
      )
      .padding(horizontal = RemoteSpacing.Large.dp, vertical = RemoteSpacing.Small.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(RemoteSpacing.Medium.dp),
  ) {
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    Switch(checked = checked, onCheckedChange = null, enabled = enabled)
  }
}
