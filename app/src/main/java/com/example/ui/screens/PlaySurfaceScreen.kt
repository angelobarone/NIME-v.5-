package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.BackingTrackStatus
import com.example.ui.components.MiniEqualizer
import com.example.ui.components.PlaySurfaceCanvas
import com.example.ui.theme.NimeBorderHairline
import com.example.ui.theme.NimeBrassGold
import com.example.ui.theme.NimeCharcoalSurface
import com.example.ui.theme.NimeCobalt
import com.example.ui.theme.NimeCopper
import com.example.ui.theme.NimeCopperSoft
import com.example.ui.theme.NimeCrimson
import com.example.ui.theme.NimeMonoTelemetry
import com.example.ui.theme.NimeObsidianBg
import com.example.ui.theme.NimePanelSurface
import com.example.ui.theme.NimeRecessedSurface
import com.example.ui.theme.NimeSage
import com.example.ui.theme.NimeTextMuted
import com.example.ui.theme.NimeTextPrimary
import com.example.ui.theme.NimeTextSecondary
import com.example.viewmodel.NimeScreen
import com.example.viewmodel.NimeUiState
import java.util.Locale

/**
 * Clean Play Surface Screen:
 * 1. Compact Top Telemetry Strip (active preset, scale & polyphonic voices)
 * 2. Full-bleed 2D Vector Canvas
 * 3. Floating Home & Record controls that adapt cleanly between Portrait and Landscape
 */
@Composable
fun PlaySurfaceScreen(
    state: NimeUiState,
    onTouchStart: (Long, Float, Float) -> Unit,
    onTouchMove: (Long, Float, Float) -> Unit,
    onTouchEnd: (Long) -> Unit,
    onClearAllTouches: () -> Unit = {},
    readWaveform: (FloatArray) -> Float = { 0f },
    onToggleRecord: (String?) -> Unit,
    onDiscardRecordingAndHome: () -> Unit = {},
    onToggleBackingTrackMute: () -> Unit = {},
    onNavigate: (NimeScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRecordNamingDialog by remember { mutableStateOf(false) }
    var navigateHomeAfterSave by remember { mutableStateOf(false) }
    var showExitGuardDialog by remember { mutableStateOf(false) }
    var recordingNameInput by remember { mutableStateOf("") }

    val handleExitAttempt = {
        if (state.isScrapbookRecording) {
            showExitGuardDialog = true
        } else {
            onNavigate(NimeScreen.HOME_SETUP)
        }
    }

    BackHandler {
        handleExitAttempt()
    }

    val infiniteTransition = rememberInfiniteTransition(label = "nime_musical_flow")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(4800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )
    val barBounce by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar_bounce"
    )

    val activePointerIds = remember { mutableSetOf<Long>() }
    val latestOnStart by rememberUpdatedState(onTouchStart)
    val latestOnMove by rememberUpdatedState(onTouchMove)
    val latestOnEnd by rememberUpdatedState(onTouchEnd)
    val latestOnClearAll by rememberUpdatedState(onClearAllTouches)
    val windowInfo = LocalWindowInfo.current

    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused && activePointerIds.isNotEmpty()) {
            activePointerIds.clear()
            latestOnClearAll()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (activePointerIds.isNotEmpty()) {
            activePointerIds.clear()
            latestOnClearAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (activePointerIds.isNotEmpty()) {
                activePointerIds.clear()
                latestOnClearAll()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NimeObsidianBg)
    ) {
        // ZONE 1: Compact Top Telemetry Strip
        Surface(
            color = NimeCharcoalSurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.activeTouches.isNotEmpty()) {
                            MiniEqualizer(
                                isPlaying = true,
                                bounce = barBounce,
                                color = NimeSage,
                                modifier = Modifier.padding(end = 10.dp)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(NimeTextMuted)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }

                        Column {
                            Text(
                                text = state.activePreset.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = NimeTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val fxTags = buildList {
                                if (state.activeSynthParams.bitcrusher > 0) add("BIT ${state.activeSynthParams.bitcrusher}")
                                if (state.activeSynthParams.distortionAmount > 0.05f) add("DIST")
                                if (state.activeSynthParams.delayTime >= 0.05f) add("DLY")
                            }
                            val fxSuffix = if (fxTags.isNotEmpty()) " · ${fxTags.joinToString("/")}" else ""
                            Text(
                                text = "${state.activeSynthParams.oscillatorType.displayLabel.uppercase()} · " +
                                    "${state.baseCutoffHz} HZ · " +
                                    state.activeScale.displayName.uppercase() + fxSuffix,
                                style = NimeMonoTelemetry.copy(fontSize = 10.sp),
                                color = NimeCobalt,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val reverbPercent = (state.effectiveReverbWet * 100f).toInt().coerceIn(0, 100)
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NimeRecessedSurface,
                            border = BorderStroke(
                                1.dp,
                                if (reverbPercent > 60) NimeCobalt.copy(alpha = 0.65f) else NimeBorderHairline
                            )
                        ) {
                            Text(
                                text = "REV $reverbPercent%",
                                style = NimeMonoTelemetry.copy(fontWeight = FontWeight.Bold),
                                color = if (reverbPercent > 55) NimeCobalt else NimeTextSecondary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = NimeRecessedSurface,
                            border = BorderStroke(
                                1.dp,
                                if (state.activeTouches.isNotEmpty()) NimeSage.copy(alpha = 0.6f) else NimeBorderHairline
                            )
                        ) {
                            val touchCount = state.activeTouches.size
                            val statusLabel = when {
                                touchCount == 1 -> "1 VOICE"
                                touchCount > 1 -> "$touchCount VOICES"
                                else -> "READY"
                            }
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = statusLabel,
                                    style = NimeMonoTelemetry.copy(fontWeight = FontWeight.Bold),
                                    color = if (touchCount > 0) NimeSage else NimeTextSecondary
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = NimeBorderHairline, thickness = 1.dp)
            }
        }

        // ZONE 2: Full-Bleed Play Surface + Floating Adaptive Home & Record Buttons
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(NimeObsidianBg)
        ) {
            val isLandscape = maxWidth > maxHeight

            // Continuous Multi-Touch Surface
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("continuous_play_surface")
                    .pointerInput(Unit) {
                        try {
                            val pressedIdsInEvent = HashSet<Long>(12)
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val canvasWidth = size.width.toFloat().coerceAtLeast(1f)
                                    val canvasHeight = size.height.toFloat().coerceAtLeast(1f)
                                    pressedIdsInEvent.clear()

                                    event.changes.forEach { pointerChange ->
                                        val pointerId = pointerChange.id.value
                                        val normX = (pointerChange.position.x / canvasWidth).coerceIn(0f, 1f)
                                        val normY = (pointerChange.position.y / canvasHeight).coerceIn(0f, 1f)

                                        if (pointerChange.pressed && !pointerChange.isConsumed) {
                                            pressedIdsInEvent.add(pointerId)
                                            if (activePointerIds.add(pointerId)) {
                                                latestOnStart(pointerId, normX, normY)
                                            } else if (pointerChange.positionChanged()) {
                                                latestOnMove(pointerId, normX, normY)
                                            }
                                            pointerChange.consume()
                                        } else if (!pointerChange.pressed) {
                                            if (activePointerIds.remove(pointerId)) {
                                                latestOnEnd(pointerId)
                                            }
                                            pointerChange.consume()
                                        }
                                    }

                                    // Reconcile any pointer dropped or cancelled by system multi-finger gestures
                                    if (activePointerIds.isNotEmpty()) {
                                        val iterator = activePointerIds.iterator()
                                        while (iterator.hasNext()) {
                                            val activeId = iterator.next()
                                            if (activeId !in pressedIdsInEvent) {
                                                iterator.remove()
                                                latestOnEnd(activeId)
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            if (activePointerIds.isNotEmpty()) {
                                activePointerIds.toList().forEach { id ->
                                    latestOnEnd(id)
                                }
                                activePointerIds.clear()
                            }
                        }
                    }
            ) {
                PlaySurfaceCanvas(
                    scaleNotes = state.activeScaleNotes,
                    activeTouches = state.activeTouches.values.toList(),
                    tiltZ = state.tiltZ,
                    effectiveReverbWet = state.effectiveReverbWet,
                    wavePhase = wavePhase,
                    pulsePhase = pulsePhase,
                    readWaveform = readWaveform
                )

                // Idle Affordance Cue (fades out immediately when touching)
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.activeTouches.isEmpty(),
                    enter = fadeIn(tween(220)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NimeCharcoalSurface.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, NimeBorderHairline)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = NimeCopper,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "TOUCH & GLIDE TO PLAY",
                                style = MaterialTheme.typography.labelLarge,
                                color = NimeTextPrimary
                            )
                        }
                    }
                }
            }

            // Top-Right Backing Track Status Pill / Mute Toggle Chip
            BackingTrackStatusChip(
                status = state.backingTrackStatus,
                fadeProgress = state.backingTrackFadeProgress,
                isMuted = state.isBackingTrackMuted,
                onToggleMute = onToggleBackingTrackMute,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
            )

            // Floating Adaptive Controls: Home + Record (Portrait: Bottom Row | Landscape: Right Column)
            val onRecordClick = {
                if (state.isScrapbookRecording) {
                    navigateHomeAfterSave = false
                    showRecordNamingDialog = true
                } else {
                    onToggleRecord(null)
                }
            }

            if (isLandscape) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    FloatingHomeButton(
                        onClick = handleExitAttempt,
                        modifier = Modifier.widthIn(min = 124.dp)
                    )
                    FloatingRecordButton(
                        isRecording = state.isScrapbookRecording,
                        recordingDuration = state.recordingDuration,
                        onClick = onRecordClick,
                        modifier = Modifier.widthIn(min = 124.dp)
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingHomeButton(
                        onClick = handleExitAttempt,
                        modifier = Modifier.widthIn(min = 120.dp)
                    )
                    FloatingRecordButton(
                        isRecording = state.isScrapbookRecording,
                        recordingDuration = state.recordingDuration,
                        onClick = onRecordClick,
                        modifier = Modifier.widthIn(min = 140.dp)
                    )
                }
            }
        }
    }

    // Recording Safety Guard Dialog
    if (showExitGuardDialog) {
        AlertDialog(
            onDismissRequest = { showExitGuardDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = NimeBrassGold
                )
            },
            title = {
                Text(
                    text = "Recording in Progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = NimeTextPrimary
                )
            },
            text = {
                Text(
                    text = "You have an active ${String.format(Locale.US, "%.1fs", state.recordingDuration)} capture. Save it to Downloads before returning Home?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NimeTextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitGuardDialog = false
                        navigateHomeAfterSave = true
                        showRecordNamingDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NimeCopper,
                        contentColor = NimeObsidianBg
                    )
                ) {
                    Text("Save & Exit", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            showExitGuardDialog = false
                            onDiscardRecordingAndHome()
                        }
                    ) {
                        Text("Discard", color = NimeCrimson)
                    }
                    TextButton(onClick = { showExitGuardDialog = false }) {
                        Text("Keep Recording", color = NimeTextSecondary)
                    }
                }
            },
            containerColor = NimePanelSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Save Recording to Downloads Dialog
    if (showRecordNamingDialog) {
        AlertDialog(
            onDismissRequest = {
                showRecordNamingDialog = false
                navigateHomeAfterSave = false
            },
            title = {
                Text(
                    text = "Save Recording to Downloads",
                    style = MaterialTheme.typography.titleMedium,
                    color = NimeTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Name your WAV take (saved in your device Downloads folder):",
                        style = MaterialTheme.typography.bodySmall,
                        color = NimeTextSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = recordingNameInput,
                        onValueChange = { recordingNameInput = it },
                        placeholder = {
                            Text(
                                "${state.activePreset.name} Vector",
                                color = NimeTextMuted
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = NimeRecessedSurface,
                            unfocusedContainerColor = NimeRecessedSurface,
                            focusedBorderColor = NimeCopper,
                            unfocusedBorderColor = NimeBorderHairline,
                            focusedTextColor = NimeTextPrimary,
                            unfocusedTextColor = NimeTextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val nameToSave = recordingNameInput.ifBlank { null }
                        val shouldExit = navigateHomeAfterSave
                        showRecordNamingDialog = false
                        navigateHomeAfterSave = false
                        recordingNameInput = ""
                        onToggleRecord(nameToSave)
                        if (shouldExit) {
                            onNavigate(NimeScreen.HOME_SETUP)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NimeCopper,
                        contentColor = NimeObsidianBg
                    )
                ) {
                    Text("Save WAV", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val shouldExit = navigateHomeAfterSave
                        showRecordNamingDialog = false
                        navigateHomeAfterSave = false
                        recordingNameInput = ""
                        onToggleRecord(null)
                        if (shouldExit) {
                            onNavigate(NimeScreen.HOME_SETUP)
                        }
                    }
                ) {
                    Text("Auto Name", color = NimeTextSecondary)
                }
            },
            containerColor = NimePanelSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
private fun FloatingHomeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = NimePanelSurface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, NimeBorderHairline),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = NimeTextPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag("btn_nav_home")
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Return to Home",
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Home",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun BackingTrackStatusChip(
    status: BackingTrackStatus,
    fadeProgress: Float,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = status == BackingTrackStatus.GENERATING ||
        status == BackingTrackStatus.FADING_IN ||
        status == BackingTrackStatus.PLAYING ||
        status == BackingTrackStatus.MUTED

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
        modifier = modifier
    ) {
        when (status) {
            BackingTrackStatus.GENERATING -> {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = NimePanelSurface.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, NimeCopper.copy(alpha = 0.55f)),
                    shadowElevation = 6.dp,
                    modifier = Modifier.testTag("backing_track_status_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            color = NimeCopperSoft,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.backing_track_creating),
                            style = NimeMonoTelemetry.copy(fontSize = 11.sp),
                            color = NimeCopperSoft,
                            maxLines = 1
                        )
                    }
                }
            }
            BackingTrackStatus.FADING_IN,
            BackingTrackStatus.PLAYING,
            BackingTrackStatus.MUTED -> {
                val activeColor = if (isMuted) NimeTextSecondary else NimeSage
                val borderColor = if (isMuted) NimeBorderHairline else NimeSage.copy(alpha = 0.65f)
                val labelText = when {
                    isMuted -> stringResource(R.string.backing_track_muted)
                    status == BackingTrackStatus.FADING_IN ->
                        "${stringResource(R.string.backing_track_fading_in)} ${(fadeProgress * 100).toInt()}%"
                    else -> stringResource(R.string.backing_track_on)
                }

                Surface(
                    onClick = onToggleMute,
                    shape = RoundedCornerShape(24.dp),
                    color = NimePanelSurface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, borderColor),
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("btn_toggle_backing_track")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = stringResource(
                                if (isMuted) R.string.backing_track_unmute_cd else R.string.backing_track_mute_cd
                            ),
                            tint = activeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = labelText,
                            style = NimeMonoTelemetry.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = activeColor,
                            maxLines = 1
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun FloatingRecordButton(
    isRecording: Boolean,
    recordingDuration: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (isRecording) NimeCrimson else NimePanelSurface.copy(alpha = 0.92f),
        border = BorderStroke(
            1.dp,
            if (isRecording) NimeTextPrimary.copy(alpha = 0.35f) else NimeBorderHairline
        ),
        shadowElevation = 10.dp,
        modifier = modifier
    ) {
        Button(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = NimeTextPrimary
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            modifier = Modifier
                .height(48.dp)
                .testTag("btn_quick_capture")
        ) {
            if (isRecording) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = NimeTextPrimary,
                    modifier = Modifier.size(19.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "STOP · ${String.format(Locale.US, "%.1fs", recordingDuration)}",
                    style = NimeMonoTelemetry.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    color = NimeTextPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.FiberManualRecord,
                    contentDescription = "Start Recording WAV",
                    tint = NimeCrimson,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "REC WAV",
                    style = MaterialTheme.typography.labelLarge,
                    color = NimeTextPrimary
                )
            }
        }
    }
}
