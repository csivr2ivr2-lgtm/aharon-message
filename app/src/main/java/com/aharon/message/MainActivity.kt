package com.aharon.message

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aharon.message.ui.AharonMessageRoot
import com.aharon.message.ui.theme.AharonMessageTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AharonMessageApp).container
        setContent {
            AharonMessageTheme {
                AharonMessageRoot(container)
            }
        }
    }
}
