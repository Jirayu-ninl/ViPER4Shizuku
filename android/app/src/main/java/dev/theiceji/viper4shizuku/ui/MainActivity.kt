package dev.theiceji.viper4shizuku.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.theiceji.viper4shizuku.service.ViperService
import dev.theiceji.viper4shizuku.ui.navigation.ViperNavigation
import dev.theiceji.viper4shizuku.ui.theme.ViperTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViperService.startService(this)
        enableEdgeToEdge()
        setContent {
            ViperTheme {
                ViperNavigation()
            }
        }
    }
}
