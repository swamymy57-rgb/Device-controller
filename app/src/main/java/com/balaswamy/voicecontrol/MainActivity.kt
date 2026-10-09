package com.balaswamy.voicecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.balaswamy.voicecontrol.ui.VoiceControlApp
import com.balaswamy.voicecontrol.ui.theme.VoiceControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoiceControlTheme {
                VoiceControlApp()
            }
        }
    }
}
