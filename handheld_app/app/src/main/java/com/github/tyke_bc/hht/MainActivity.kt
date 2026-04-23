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
import androidx.compose.ui.platform.LocalContext
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
        var loggedInUser: String = "Default User"
        var loggedInRole: String = "SA"
        var loggedInEid: String = ""

        // Remembered across sessions by LoginScreen. COMPASS/RESPOND launch without a fresh
        // login, so they rely on this to know which store's data to pull.
        fun lastStoreId(context: Context): String =
            context.getSharedPreferences("hht_prefs", Context.MODE_PRIVATE)
                .getString("last_store_id", "") ?: ""
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
    val context = LocalContext.current

    NavHost(navController = navController, startDestination = "launcher") {
        composable("launcher") {
            LauncherScreen(onOpenApp = { appName ->
                when (appName) {
                    "HHT" -> navController.navigate("login")
                    // COMPASS/RESPOND have no login — fall back to login if this device has never
                    // picked a store, so we never send an empty X-Store-ID.
                    "COMPASS" -> {
                        val sid = MainActivity.lastStoreId(context)
                        if (sid.isNotEmpty()) navController.navigate("scan/$sid")
                        else navController.navigate("login")
                    }
                    "RESPOND" -> {
                        val sid = MainActivity.lastStoreId(context)
                        if (sid.isNotEmpty()) navController.navigate("respond/$sid")
                        else navController.navigate("login")
                    }
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
            // Route args are always set by nav calls above — empty string would fail the API
            // with a clear "No Store ID provided" rather than silently querying the wrong store.
            val storeId = backStackEntry.arguments?.getString("storeId") ?: ""
            ScanScreen(storeId = storeId, onBackToLauncher = {
                navController.popBackStack("launcher", inclusive = false)
            })
        }
        composable("respond/{storeId}") { backStackEntry ->
            val storeId = backStackEntry.arguments?.getString("storeId") ?: ""
            RespondScreen(storeId = storeId, onBack = {
                navController.popBackStack("launcher", inclusive = false)
            })
        }
    }
}