package com.github.tyke_bc.hht

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.github.tyke_bc.hht.ui.theme.HHTTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class MainActivity : ComponentActivity() {
    companion object {
        private val _scanEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val scanEvents = _scanEvents.asSharedFlow()
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.github.tyke_bc.hht.SCAN_EVENT") {
                val data = intent.getStringExtra("com.symbol.datawedge.data_string")
                if (data != null) {
                    _scanEvents.tryEmit(data)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val filter = IntentFilter("com.github.tyke_bc.hht.SCAN_EVENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(scanReceiver, filter)
        }

        setContent {
            HHTTheme {
                HHTApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(scanReceiver)
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
                    navController.navigate("scan/14302")
                }
            })
        }
        composable("login") {
            LoginScreen(onLoginSuccess = { storeId ->
                navController.navigate("scan/$storeId") {
                    popUpTo("login") { inclusive = true }
                }
            })
        }
        composable("scan/{storeId}") { backStackEntry ->
            val storeId = backStackEntry.arguments?.getString("storeId") ?: "14302"
            ScanScreen(storeId = storeId, onBackToLauncher = {
                navController.popBackStack("launcher", inclusive = false)
            })
        }
    }
}