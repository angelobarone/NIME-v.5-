package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.screens.HomeSetupScreen
import com.example.ui.screens.PlaySurfaceScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.NimeObsidianBg
import com.example.ui.theme.NimePanelSurface
import com.example.ui.theme.NimeSage
import com.example.ui.theme.NimeTextPrimary
import com.example.viewmodel.NimeScreen
import com.example.viewmodel.NimeViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: NimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(uiState.statusMessage) {
                    uiState.statusMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = NimeObsidianBg,
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = {
                        SnackbarHost(snackbarHostState) { data ->
                            Snackbar(
                                snackbarData = data,
                                containerColor = NimePanelSurface,
                                contentColor = NimeTextPrimary,
                                actionColor = NimeSage,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (uiState.currentScreen) {
                            NimeScreen.HOME_SETUP -> {
                                HomeSetupScreen(
                                    state = uiState,
                                    onMoodChanged = { viewModel.updateMoodInput(it) },
                                    onSelectPreset = { viewModel.selectPreset(it) },
                                    onTuneAndPlay = { viewModel.tuneAndPlay(it) },
                                    onAbortGeneration = { viewModel.abortGeneration() },
                                    onUpdateBackendUrl = { viewModel.updateBackendUrl(it) },
                                    onDismissError = { viewModel.dismissBackendError() },
                                    onRetry = { viewModel.retryBackendGeneration() },
                                    onNavigate = { viewModel.navigateTo(it) }
                                )
                            }
                            NimeScreen.PLAY_SURFACE -> {
                                PlaySurfaceScreen(
                                    state = uiState,
                                    onTouchStart = { id, x, y -> viewModel.onTouchStart(id, x, y) },
                                    onTouchMove = { id, x, y -> viewModel.onTouchMove(id, x, y) },
                                    onTouchEnd = { id -> viewModel.onTouchEnd(id) },
                                    onClearAllTouches = { viewModel.onClearAllTouches() },
                                    readWaveform = { dest -> viewModel.readAudioWaveform(dest) },
                                    onToggleRecord = { name -> viewModel.toggleScrapbookRecording(name) },
                                    onDiscardRecordingAndHome = { viewModel.discardRecordingAndNavigateHome() },
                                    onToggleBackingTrackMute = { viewModel.toggleBackingTrackMute() },
                                    onNavigate = { viewModel.navigateTo(it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
