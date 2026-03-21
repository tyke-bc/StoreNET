package com.github.tyke_bc.hht

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.tyke_bc.hht.ui.theme.HHTTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HHTTheme {
                HHTApp()
            }
        }
    }
}

@Composable
fun HHTApp() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "launcher") {
        composable("launcher") {
            LauncherScreen(onOpenApp = { appName ->
                if (appName == "HHT") {
                    navController.navigate("login")
                } else if (appName == "COMPASS") {
                    navController.navigate("scan")
                }
            })
        }
        composable("login") {
            LoginScreen(onLoginSuccess = {
                navController.navigate("scan") {
                    popUpTo("login") { inclusive = true }
                }
            })
        }
        composable("scan") {
            ScanScreen(onBackToLauncher = {
                navController.popBackStack("launcher", inclusive = false)
            })
        }
    }
}