package belowevolve.airremote

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import belowevolve.shared.design.RemotePalette
import belowevolve.shared.design.RemoteShapes as SharedShapes

private val RemoteDarkColors = darkColorScheme(
    primary = Color(RemotePalette.Primary),
    onPrimary = Color(RemotePalette.OnPrimary),
    primaryContainer = Color(0xFF315318),
    onPrimaryContainer = Color(0xFFD3FFB6),
    secondary = Color(0xFFC7D9B6),
    onSecondary = Color(0xFF2C3826),
    background = Color(RemotePalette.Background),
    onBackground = Color(RemotePalette.Text),
    surface = Color(RemotePalette.Surface),
    onSurface = Color(RemotePalette.Text),
    surfaceVariant = Color(0xFF41493C),
    onSurfaceVariant = Color(RemotePalette.Muted),
    outline = Color(0xFF8B9383),
)

object RemoteColors {
    val PowerContainer = Color(RemotePalette.PowerContainer)
    val Power = Color(RemotePalette.Power)
    val Red = Color(RemotePalette.Red)
    val Green = Color(RemotePalette.Green)
    val Yellow = Color(RemotePalette.Yellow)
    val Blue = Color(RemotePalette.Blue)
}

private val RemoteShapes = Shapes(
    extraSmall = RoundedCornerShape(SharedShapes.ExtraSmall.dp),
    small = RoundedCornerShape(SharedShapes.Small.dp),
    medium = RoundedCornerShape(SharedShapes.Medium.dp),
    large = RoundedCornerShape(SharedShapes.Large.dp),
    extraLarge = RoundedCornerShape(SharedShapes.ExtraLarge.dp),
)

private val RemoteTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun RemoteTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        RemoteDarkColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = RemoteTypography,
        shapes = RemoteShapes,
        content = content,
    )
}
