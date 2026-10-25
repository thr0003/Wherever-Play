package com.whereverplay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.whereverplay.app.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // WP: 全部界面在 AppRoot 里组织；Demo 用状态路由，不引 navigation-compose
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(Color(0xFF0E0E12))
            ) {
                AppRoot()
            }
        }
    }
}
