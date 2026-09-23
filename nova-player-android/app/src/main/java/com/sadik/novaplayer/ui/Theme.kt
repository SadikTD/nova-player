package com.sadik.novaplayer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Desktop theme tokens (styles.css), carried over so both apps feel like one product. */
object Nova {
    val Bg = Color(0xFF070A10)
    val Bg2 = Color(0xFF0C1017)
    val Bg3 = Color(0xFF151B26)
    val Card = Color(0xFF111721)
    val CardHover = Color(0xFF18202E)
    val Line = Color(0x12FFFFFF)
    val Text = Color(0xFFEAEEF5)
    val Dim = Color(0xFF8892A5)
    val Danger = Color(0xFFF04F43)
    val Boost = Color(0xFFFBBF24)
}

/** The desktop's six accents: key, light, deep. Stored as an index in prefs ("accent"). */
@Immutable data class Accent(val key: String, val name: String, val light: Color, val deep: Color) {
    val grad get() = Brush.linearGradient(listOf(light, deep))
    val soft get() = light.copy(alpha = .12f)
    val soft2 get() = light.copy(alpha = .2f)
    val line get() = light.copy(alpha = .4f)
    val glow get() = light.copy(alpha = .45f)
}

val Accents = listOf(
    Accent("blue", "Nova blue", Color(0xFF4FACFE), Color(0xFF2F6BFF)),
    Accent("violet", "Violet", Color(0xFFA78BFA), Color(0xFF6D28D9)),
    Accent("emerald", "Emerald", Color(0xFF34D399), Color(0xFF059669)),
    Accent("amber", "Amber", Color(0xFFFBBF24), Color(0xFFD97706)),
    Accent("rose", "Rose", Color(0xFFFB7185), Color(0xFFE11D48)),
    Accent("cyan", "Cyan", Color(0xFF22D3EE), Color(0xFF0891B2)),
)

val LocalAccent = staticCompositionLocalOf { Accents[0] }

@Composable fun NovaTheme(accent: Accent, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent.light, onPrimary = Color(0xFF04101F), secondary = accent.deep,
        primaryContainer = accent.soft2, onPrimaryContainer = Nova.Text,
        secondaryContainer = accent.soft2, onSecondaryContainer = Nova.Text,
        background = Nova.Bg, onBackground = Nova.Text, surface = Nova.Card, onSurface = Nova.Text,
        surfaceVariant = Nova.Bg3, onSurfaceVariant = Nova.Dim, surfaceContainer = Nova.Bg2,
        surfaceContainerHigh = Nova.Bg3, surfaceContainerHighest = Nova.CardHover, surfaceContainerLow = Nova.Bg2,
        outline = Color(0x33FFFFFF), outlineVariant = Nova.Line, error = Nova.Danger,
    )
    val base = Typography()
    val type = Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        CompositionLocalProvider(LocalAccent provides accent, androidx.compose.material3.LocalContentColor provides Nova.Text, content = content)
    }
}

val Kicker = TextStyle(fontSize = 11.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Bold)
