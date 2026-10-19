package dev.theiceji.viper4shizuku.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.theiceji.viper4shizuku.ui.screens.main.MainScreen

@Composable
fun ViperNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "main",
    ) {
        composable("main") {
            MainScreen()
        }
    }
}
