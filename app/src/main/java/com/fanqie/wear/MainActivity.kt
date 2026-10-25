package com.fanqie.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.AppScaffold
import com.fanqie.wear.ui.FanqieWearApp
import com.fanqie.wear.ui.theme.FanqieWearTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FanqieWearTheme {
                AppScaffold(modifier = Modifier.fillMaxSize()) {
                    FanqieWearApp()
                }
            }
        }
    }
}
