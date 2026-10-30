package com.cyj265.installments.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
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
    // muted 承担每一屏的次要行（日期、期数、金额副文案），11sp 上用它，低于 4.5:1 就直接糊掉
    muted = Color(0xFF5D6E68),
    line = Color(0xFFD2CEC1),
    brand = Color(0xFF0E3B34),
    onBrand = Color(0xFFF4F2ED),
    done = Color(0xFF1F7A61),
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
    // 分隔线只是"看得见有这条线"，不是文字，所以按非元素 3:1 都不要求；
    // 但 1.44:1 时行与行完全没有边界，一屏小字会连成一片，故抬到 1.86。
    line = Color(0xFF3A4742),
    brand = Color(0xFF8AD9BE),
    onBrand = Color(0xFF0C1715),
    done = Color(0xFF5CC8A3),
    nowBg = Color(0xFF3A3220),
    nowFg = Color(0xFFF5CE7A),
    lateBg = Color(0xFF3B241F),
    lateFg = Color(0xFFF2A58E),
    tickIdle = Color(0xFF33403B),
)

/**
 * 必须是 compositionLocalOf：调色板会随明暗切换变值。
 * 之前用 staticCompositionLocalOf，它假定值永不变、换值时不通知读取方，于是系统从浅色切到深色后，
 * 早就组合好的根容器还留着 LightFq.paper，之后新组合的卡片和刻度块已经是 DarkFq 了——
 * 真机截图量到同一帧里 #F4F2ED 的页面压着 #19221F 的卡片，浅底上写浅字，整片文字看不见。
 */
val LocalFqColors = compositionLocalOf { LightFq }

/**
 * 每个文字样式都必须自带颜色。Text 只传 `style = TextStyle(fontSize = …)` 时，
 * 色值一路退到 LocalTextStyle，再退到平台默认的纯黑——浅色模式看不出来，
 * 暗色模式下黑字压黑底直接隐形，实测截图里那批「看不太清」的数字就是这么来的。
 */
private fun fqTypography(fq: FqColors) = Typography(
    displaySmall = TextStyle(color = fq.ink, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum"),
    titleMedium = TextStyle(color = fq.ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(color = fq.ink, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(color = fq.ink, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    bodySmall = TextStyle(color = fq.ink, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    labelMedium = TextStyle(color = fq.ink, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(color = fq.ink, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
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
    val typography = fqTypography(fq)
    CompositionLocalProvider(LocalFqColors provides fq) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = FqShapes) {
            // 界面里大量 Text 只写 `style = TextStyle(fontSize = …)`。注意：显式传 style 时
            // material3 的 Text 不会读 LocalTextStyle（那个参数只在该形参没传时才生效），
            // 于是 color 一路 Unspecified，最后落到 BasicText 的兜底——LocalContentColor，
            // 而它的默认值是纯黑。只提供 LocalTextStyle 治不到这批调用，必须提供 LocalContentColor。
            CompositionLocalProvider(
                LocalTextStyle provides typography.bodyMedium,
                LocalContentColor provides fq.ink,
                content = content,
            )
        }
    }
}
