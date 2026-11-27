package app.sillon.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFFE5233F)
val HiResGold = Color(0xFFF2C14E)
val AtmosBlue = Color(0xFF5B8DEF)

private val Light = lightColorScheme(
    primary = Accent, onPrimary = Color.White,
    background = Color.White, onBackground = Color(0xFF111114),
    surface = Color.White, onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFF4F4F6), onSurfaceVariant = Color(0xFF6B6B73),
    outlineVariant = Color(0xFFE4E4E8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF4D66), onPrimary = Color.White,
    background = Color(0xFF000000), onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF000000), onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1C1C20), onSurfaceVariant = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFF2A2A30),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun SillonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
