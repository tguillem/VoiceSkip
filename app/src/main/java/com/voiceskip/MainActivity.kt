// SPDX-License-Identifier: GPL-3.0-or-later

package com.voiceskip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voiceskip.ui.main.MainScreen
import com.voiceskip.ui.main.MainScreenViewModel
import com.voiceskip.ui.settings.SettingsScreen
import com.voiceskip.ui.theme.VoiceSkipTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val newIntentChannel = Channel<Intent>(Channel.UNLIMITED)

    @Inject
    lateinit var startupConfig: StartupConfig

    companion object {
        const val EXTRA_SKIP_MODEL_LOAD = "skip_model_load"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startupConfig.skipModelLoad = intent.getBooleanExtra(EXTRA_SKIP_MODEL_LOAD, false)

        setContent {
            VoiceSkipTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        val viewModel: MainScreenViewModel = hiltViewModel()

                        LaunchedEffect(Unit) {
                            viewModel.handleIncomingIntent(intent)
                            intent.action = null
                            intent.data = null
                        }

                        LaunchedEffect(Unit) {
                            newIntentChannel.receiveAsFlow().collect { newIntent ->
                                viewModel.handleIncomingIntent(newIntent)
                                newIntent.action = null
                                newIntent.data = null
                            }
                        }

                        MainScreen(
                            onNavigateToSettings = {
                                navController.navigate("settings")
                            }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        newIntentChannel.trySend(intent)
    }
}