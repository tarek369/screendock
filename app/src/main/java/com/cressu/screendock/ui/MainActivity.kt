package com.cressu.screendock.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.cressu.screendock.bridge.NativeBridge
import com.cressu.screendock.service.AirPlayService
import com.cressu.screendock.ui.theme.AirplayTheme

private const val TAG = "AirplayMainActivity"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: swaps the launch splash theme for
        // postSplashScreenTheme (Theme.Airplay) at the right moment.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            // The UI is always dark — force light system-bar icons regardless
            // of the system theme, with transparent scrims over the aurora.
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val version = NativeBridge.versionString()
        Log.i(TAG, "Native version: $version")

        setContent {
            AirplayTheme {
                AppScreen(version = version, context = applicationContext)
            }
        }

        if (NativeBridge.isLoaded() && NativeBridge.selfTest()) {
            AirPlayService.start(this)
        } else {
            Log.e(TAG, "native selfTest failed; not starting service")
        }
    }
}
