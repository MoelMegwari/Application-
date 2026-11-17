package com.circuitgraph.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.circuitgraph.app.R

val SpaceGroteskFontFamily = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.SemiBold),
    Font(R.font.space_grotesk, FontWeight.Bold)
)

val PlusJakartaSansFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold)
)

val JetBrainsMonoFontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium)
)

val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceGroteskFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSansFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 13.sp
    )
)

val AppShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF04242C),
    primaryContainer = Color(0xFF0E4A5C),
    onPrimaryContainer = Color(0xFFB5F4FF),
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF02261B),
    secondaryContainer = Color(0xFF0C4A36),
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color(0xFF2E1B00),
    tertiaryContainer = Color(0xFF523200),
    onTertiaryContainer = Color(0xFFFDE68A),
    error = Color(0xFFF43F5E),
    onError = Color(0xFF33000B),
    errorContainer = Color(0xFF641224),
    onErrorContainer = Color(0xFFFFD9DF),
    background = Color(0xFF0B111A),
    onBackground = Color(0xFFE6EDF6),
    surface = Color(0xFF111824),
    onSurface = Color(0xFFE6EDF6),
    surfaceVariant = Color(0xFF1B2536),
    onSurfaceVariant = Color(0xFF9FB0C7),
    outline = Color(0xFF2D3C54),
    outlineVariant = Color(0xFF1F2B3D)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0284C7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF059669),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF92400E),
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF9F1239),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE9EFF7),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

data class CircuitSemanticColors(
    val canvasBackground: Color,
    val canvasGrid: Color,
    val logicOne: Color,
    val logicZero: Color,
    val logicX: Color,
    val wireIdle: Color,
    val gateInput: Color,
    val gateAnd: Color,
    val gateOr: Color,
    val gateXor: Color,
    val gateNot: Color,
    val gateBlackbox: Color
)

private val DarkSemantic = CircuitSemanticColors(
    canvasBackground = Color(0xFF090E16),
    canvasGrid = Color(0xFF162030),
    logicOne = Color(0xFF10B981),
    logicZero = Color(0xFF64748B),
    logicX = Color(0xFFF59E0B),
    wireIdle = Color(0xFF334155),
    gateInput = Color(0xFF0284C7),
    gateAnd = Color(0xFF0891B2),
    gateOr = Color(0xFF6366F1),
    gateXor = Color(0xFF8B5CF6),
    gateNot = Color(0xFFEC4899),
    gateBlackbox = Color(0xFFD97706)
)

private val LightSemantic = CircuitSemanticColors(
    canvasBackground = Color(0xFFF8FAFC),
    canvasGrid = Color(0xFFE2E8F0),
    logicOne = Color(0xFF059669),
    logicZero = Color(0xFF64748B),
    logicX = Color(0xFFD97706),
    wireIdle = Color(0xFFCBD5E1),
    gateInput = Color(0xFF0284C7),
    gateAnd = Color(0xFF0E7490),
    gateOr = Color(0xFF4F46E5),
    gateXor = Color(0xFF7C3AED),
    gateNot = Color(0xFFDB2777),
    gateBlackbox = Color(0xFFB45309)
)

val LocalCircuitColors = staticCompositionLocalOf { DarkSemantic }

@Composable
fun CircuitGraphTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val semantic = if (darkTheme) DarkSemantic else LightSemantic

    CompositionLocalProvider(LocalCircuitColors provides semantic) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
