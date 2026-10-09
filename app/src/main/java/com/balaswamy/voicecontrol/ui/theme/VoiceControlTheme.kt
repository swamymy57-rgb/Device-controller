package com.balaswamy.voicecontrol.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4559C7),
    onPrimary = Color.White,
    secondary = Color(0xFF59617A),
    background = Color(0xFFF7F7FC),
    surface = Color(0xFFF7F7FC),
    surfaceContainer = Color(0xFFFFFFFF),
)

@Composable
fun VoiceControlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
