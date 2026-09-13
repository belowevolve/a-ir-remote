package dev.air.remote

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

private val RemoteDarkColors = darkColorScheme(
    primary = Color(0xFFB8F397),
    onPrimary = Color(0xFF183800),
    primaryContainer = Color(0xFF315318),
    onPrimaryContainer = Color(0xFFD3FFB6),
    secondary = Color(0xFFC7D9B6),
    onSecondary = Color(0xFF2C3826),
    background = Color(0xFF101411),
    onBackground = Color(0xFFE1E9DA),
    surface = Color(0xFF181D18),
    onSurface = Color(0xFFE1E9DA),
    surfaceVariant = Color(0xFF41493C),
    onSurfaceVariant = Color(0xFFC1C9B7),
    outline = Color(0xFF8B9383),
)

object RemoteColors {
    val PowerContainer = Color(0xFF55252A)
    val Power = Color(0xFFFFB3B3)
    val Red = Color(0xFFE86A70)
    val Green = Color(0xFF63B978)
    val Yellow = Color(0xFFF0C85A)
    val Blue = Color(0xFF62A9E8)
}

private val RemoteShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
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
