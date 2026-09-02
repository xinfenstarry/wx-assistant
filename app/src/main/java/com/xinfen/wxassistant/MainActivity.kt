package com.xinfen.wxassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import com.xinfen.wxassistant.ui.WxAssistantApp
import com.xinfen.wxassistant.ui.theme.WxAssistantTheme

class MainActivity : ComponentActivity() {
    private var incomingSharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeIntent(intent)
        setContent {
            WxAssistantTheme {
                WxAssistantApp(
                    incomingSharedText = incomingSharedText,
                    onSharedTextHandled = { incomingSharedText = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        incomingSharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
    }

    companion object {
        const val ACTION_OPEN_PLAN = "com.xinfen.wxassistant.action.OPEN_PLAN"
    }
}
