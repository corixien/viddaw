package io.github.corixien.viddaw

import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.corixien.viddaw.app.AppState
import io.github.corixien.viddaw.app.ThemeMode
import io.github.corixien.viddaw.engine.NativeEngine
import io.github.corixien.viddaw.ui.App
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class MainActivity : ComponentActivity() {
    private val scope = MainScope()
    private val engine = NativeEngine()
    private lateinit var platform: AndroidPlatform
    private lateinit var app: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        platform = AndroidPlatform(this)
        app = AppState(platform, engine, scope)

        val inter = FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold),
        )
        setContent {
            val systemDark = isSystemInDarkTheme()
            val dark = when (app.settings.theme) {
                ThemeMode.System -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // System bar icons must contrast with the app theme, which may differ from the system's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            BackHandler(enabled = app.studio != null || app.showSettings) { app.back() }
            App(app, systemDark, inter)
        }
    }

    override fun onStart() {
        super.onStart()
        engine.start()
        platform.midi.start()
    }

    override fun onStop() {
        super.onStop()
        // Keep running while a take is in progress; otherwise release the audio device.
        if (app.studio?.recording != true) {
            engine.pause()
            engine.stop()
            platform.midi.stop()
        }
        app.studio?.saveNow()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
