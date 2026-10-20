package com.cyj265.installments.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面稿确认过的墨绿+琥珀配色，成对给出明暗两套。
 * 刻意关掉动态取色：壁纸色会把"已还/本期/逾期"三种状态色冲淡，而这三色正是刻度块的全部信息。
 */
@Immutable
data class FqColors(
    val paper: Color,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val brand: Color,
    val onBrand: Color,
    val done: Color,
    val nowBg: Color,
    val nowFg: Color,
    val lateBg: Color,
    val lateFg: Color,
    val tickIdle: Color,
)

private val LightFq = FqColors(
    paper = Color(0xFFF4F2ED),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFFAF9F6),
    ink = Color(0xFF14211E),
    muted = Color(0xFF6C7C76),
    line = Color(0xFFE4E1D8),
    brand = Color(0xFF0E3B34),
    onBrand = Color(0xFFF4F2ED),
    done = Color(0xFF2E8B72),
    nowBg = Color(0xFFFBEFC9),
    nowFg = Color(0xFF8A5A00),
    lateBg = Color(0xFFF7E2DC),
    lateFg = Color(0xFFA8402A),
    tickIdle = Color(0xFFDEDCD3),
)

private val DarkFq = FqColors(
    paper = Color(0xFF101816),
    surface = Color(0xFF19221F),
    surface2 = Color(0xFF202B28),
    ink = Color(0xFFE9F1EC),
    muted = Color(0xFF97A8A1),
    line = Color(0xFF2A3632),
    brand = Color(0xFF8AD9BE),
    onBrand = Color(0xFF0C1715),
    done = Color(0xFF5CC8A3),
    nowBg = Color(0xFF3A3220),
    nowFg = Color(0xFFF5CE7A),
    lateBg = Color(0xFF3B241F),
    lateFg = Color(0xFFF2A58E),
    tickIdle = Color(0xFF33403B),
)

val LocalFqColors = staticCompositionLocalOf { LightFq }

private val FqTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
)

private val FqShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
)

@Composable
fun FqTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fq = if (darkTheme) DarkFq else LightFq
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = fq.brand,
            onPrimary = fq.onBrand,
            surface = fq.surface,
            onSurface = fq.ink,
            background = fq.paper,
            onBackground = fq.ink,
            outline = fq.line,
            error = fq.lateFg,
        )
    } else {
        lightColorScheme(
            primary = fq.brand,
            onPrimary = fq.onBrand,
            surface = fq.surface,
            onSurface = fq.ink,
            background = fq.paper,
            onBackground = fq.ink,
            outline = fq.line,
            error = fq.lateFg,
        )
    }
    CompositionLocalProvider(LocalFqColors provides fq) {
        MaterialTheme(colorScheme = scheme, typography = FqTypography, shapes = FqShapes, content = content)
    }
}
