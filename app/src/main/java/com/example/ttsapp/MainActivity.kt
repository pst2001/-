package com.example.ttsapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.ttsapp.ui.TtsScreen
import com.example.ttsapp.ui.theme.TtsAppTheme

/**
 * 单 Activity 架构：整个应用只有一个界面，所有状态都在 TtsViewModel 里，
 * 旋转屏幕时 ViewModel 不重建，文本 / 参数 / 朗读状态自然保留。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TtsAppTheme {
                TtsScreen()
            }
        }
    }
}
