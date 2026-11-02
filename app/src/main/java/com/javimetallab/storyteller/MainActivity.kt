package com.javimetallab.storyteller

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.javimetallab.storyteller.ui.AppFrame
import com.javimetallab.storyteller.ui.ImportScreen
import com.javimetallab.storyteller.ui.LibraryScreen
import com.javimetallab.storyteller.ui.FetchScreen
import com.javimetallab.storyteller.ui.ModelsScreen
import com.javimetallab.storyteller.ui.PronScreen
import com.javimetallab.storyteller.ui.NarrateScreen
import com.javimetallab.storyteller.ui.PlayerScreen
import com.javimetallab.storyteller.ui.StorytellerTheme
import com.javimetallab.storyteller.ui.TranslateScreen
import com.javimetallab.storyteller.ui.WebScreen

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, whatever the phone's theme: light icons and no pale bar behind them.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Without it everything still works, only without the notifications.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            StorytellerTheme {
                val vm: AppViewModel = viewModel()
                val current by vm.screen.collectAsStateWithLifecycle()
                LifecycleStartEffect(vm) {
                    vm.setForeground(true)
                    onStopOrDispose { vm.setForeground(false) }
                }
                // Reads `current` here so that this is evaluated again on every screen change.
                BackHandler(enabled = current != Screen.Library && vm.canGoBack()) { vm.back() }
                AppFrame(vm) {
                    when (val screen = current) {
                        Screen.Library -> LibraryScreen(vm)
                        Screen.Player -> PlayerScreen(vm)
                        Screen.Narrate -> NarrateScreen(vm)
                        Screen.Import -> ImportScreen(vm)
                        Screen.Translate -> TranslateScreen(vm)
                        Screen.Models -> ModelsScreen(vm)
                        Screen.Pron -> PronScreen(vm)
                        Screen.Fetch -> FetchScreen(vm)
                        is Screen.Web -> WebScreen(vm, screen)
                    }
                }
            }
        }
    }
}
