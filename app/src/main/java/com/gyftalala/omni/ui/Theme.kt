package com.gyftalala.omni.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.gyftalala.omni.data.Category

val Ink = Color(0xFF101214)
val Panel = Color(0xFF1A1D21)
val Raised = Color(0xFF25292E)
val Paper = Color(0xFFE7E4DE)
val Body = Color(0xFFC3C5C8)
val Muted = Color(0xFF999FA6)
val Outline = Color(0xFF30353B)
val Accent = Color(0xFFC8B38A)
val Mint = Color(0xFF91B9AE)

fun Category.tint() = when (this) {
    Category.REMINDER, Category.UNKNOWN -> Accent
    Category.PRODUCT -> Color(0xFF9CB7D1)
    Category.UX_DESIGN -> Color(0xFFB0A8CD)
    Category.CARD, Category.APK -> Mint
    Category.DOCUMENT, Category.NOTE -> Body
}

@Composable fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, onPrimary = Ink, primaryContainer = Raised, onPrimaryContainer = Paper, inversePrimary = Accent,
        secondary = Mint, onSecondary = Ink, secondaryContainer = Raised, onSecondaryContainer = Paper,
        tertiary = Color(0xFFB0A8CD), onTertiary = Ink, tertiaryContainer = Raised, onTertiaryContainer = Paper,
        background = Ink, onBackground = Paper, surface = Panel, onSurface = Paper,
        surfaceVariant = Raised, onSurfaceVariant = Muted, surfaceTint = Color.Transparent,
        inverseSurface = Raised, inverseOnSurface = Paper,
        error = Color(0xFFE2A198), onError = Ink, errorContainer = Color(0xFF382728), onErrorContainer = Paper,
        outline = Outline, outlineVariant = Outline, scrim = Ink,
        surfaceBright = Raised, surfaceDim = Ink, surfaceContainer = Panel,
        surfaceContainerHigh = Raised, surfaceContainerHighest = Raised,
        surfaceContainerLow = Panel, surfaceContainerLowest = Ink,
    ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-.5).sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 16.sp),
        )) {
        // Explicit charcoal surfaces keep elevation from adding unrelated color tints.
        CompositionLocalProvider(LocalTonalElevationEnabled provides false, LocalContentColor provides Paper, content = content)
    }
}

@Composable fun OmniButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier, enabled, shape = shape,
        border = BorderStroke(1.dp, Outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Raised, contentColor = Paper,
            disabledContainerColor = Panel, disabledContentColor = Muted), content = content)
}

@Composable fun OmniTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier, enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Accent, disabledContentColor = Muted), content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OmniFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(selected, onClick, label, border = BorderStroke(1.dp, if (selected) Accent.copy(alpha = .55f) else Outline),
        colors = FilterChipDefaults.filterChipColors(containerColor = Panel, labelColor = Muted,
            selectedContainerColor = Raised, selectedLabelColor = Accent))
}
