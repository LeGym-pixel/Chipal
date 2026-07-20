package com.svpn.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Strict black / green / white palette, occult-cyber mood.
val PeladuBlack = Color(0xFF000000)
val PeladuBlackElevated = Color(0xFF0A0F0A)
val PeladuGreen = Color(0xFF00FF66)
val PeladuGreenDim = Color(0xFF11592E)
val PeladuWhite = Color(0xFFEFEFEF)

private val PeladuColorScheme = darkColorScheme(
    primary = PeladuGreen,
    onPrimary = PeladuBlack,
    secondary = PeladuGreenDim,
    onSecondary = PeladuWhite,
    background = PeladuBlack,
    onBackground = PeladuWhite,
    surface = PeladuBlackElevated,
    onSurface = PeladuWhite,
    error = Color(0xFFFF3B3B),
    onError = PeladuBlack
)

@Composable
fun PeladuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PeladuColorScheme, content = content)
}
