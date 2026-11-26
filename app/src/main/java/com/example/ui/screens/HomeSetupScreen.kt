package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MoodPreset
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
import com.example.viewmodel.DEFAULT_PRESETS
import com.example.viewmodel.NimeScreen
import com.example.viewmodel.NimeUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeSetupScreen(
    state: NimeUiState,
    onMoodChanged: (String) -> Unit,
    onSelectPreset: (MoodPreset) -> Unit,
    onTuneAndPlay: (String) -> Unit,
    onAbortGeneration: () -> Unit = {},
    onUpdateBackendUrl: (String) -> Unit = {},
    onDismissError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onNavigate: ((NimeScreen) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showServerDialog by remember { mutableStateOf(false) }
    var serverUrlDraft by remember(state.backendUrl) { mutableStateOf(state.backendUrl) }
    val wordCount = state.moodWordCount
    val isMoodValid = state.isMoodValid

    val validationColor by animateColorAsState(
        targetValue = if (isMoodValid) NimeSage else NimeCrimson,
        animationSpec = tween(220),
        label = "mood_validation_color"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        NimeObsidianBg,
                        Color(0xFF13161B),
                        NimeObsidianBg
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. Hardware Brand Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    Brush.linearGradient(listOf(NimeCopper, NimeBrassGold))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "NIME Instrument Emblem",
                                tint = NimeObsidianBg,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "NIME",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    letterSpacing = 2.sp
                                ),
                                color = NimeTextPrimary
                            )
                        }
                    }

                    // Server Endpoint / Readiness Pill
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = NimeCharcoalSurface,
                        border = BorderStroke(1.dp, NimeBorderHairline),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                serverUrlDraft = state.backendUrl
                                showServerDialog = true
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isMoodValid) NimeSage else NimeCrimson)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SERVER",
                                style = NimeMonoTelemetry,
                                color = NimeTextSecondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Link,
                                contentDescription = "Configure Backend Server URL",
                                tint = NimeCobalt,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            // 3. Constrained Mood Input & Preset Hardware Panel
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = NimeCharcoalSurface),
                    border = BorderStroke(1.dp, NimeBorderHairline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "How do you feel Right Now?",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NimeTextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Single-Focus Text Input (Clear affordance replaces competing launch arrow)
                        OutlinedTextField(
                            value = state.moodInput,
                            onValueChange = onMoodChanged,
                            placeholder = {
                                Text(
                                    text = "e.g. Warm drifting dusk pad",
                                    color = NimeTextMuted
                                )
                            },
                            singleLine = true,
                            isError = !isMoodValid,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    if (isMoodValid) onTuneAndPlay(state.moodInput)
                                }
                            ),
                            trailingIcon = {
                                if (state.moodInput.isNotEmpty()) {
                                    IconButton(
                                        onClick = { onMoodChanged("") },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Clear mood descriptor",
                                            tint = NimeTextSecondary
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = NimeRecessedSurface,
                                unfocusedContainerColor = NimeRecessedSurface,
                                errorContainerColor = NimeRecessedSurface,
                                focusedBorderColor = if (isMoodValid) NimeSage else NimeCrimson,
                                unfocusedBorderColor = if (isMoodValid) NimeBorderHairline else NimeCrimson,
                                errorBorderColor = NimeCrimson,
                                focusedTextColor = NimeTextPrimary,
                                unfocusedTextColor = NimeTextPrimary,
                                cursorColor = NimeCopper
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_mood_descriptor")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Explicit Constraint Feedback Line
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (isMoodValid) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = validationColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (wordCount == 0) {
                                    "Enter a mood description to unlock synthesis."
                                } else {
                                    "Ready to synthesize your sound world."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = validationColor
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Tactile Preset Selector Pads
                        Text(
                            text = "Preset Sound Worlds",
                            style = MaterialTheme.typography.labelMedium,
                            color = NimeTextSecondary,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            DEFAULT_PRESETS.forEach { preset ->
                                val isSelected = state.activePreset.id == preset.id &&
                                    state.moodInput.trim().equals(preset.name, ignoreCase = true)
                                val padBg by animateColorAsState(
                                    targetValue = if (isSelected) NimeCopper.copy(alpha = 0.18f) else NimePanelSurface,
                                    animationSpec = tween(180),
                                    label = "pad_bg_${preset.id}"
                                )
                                val padBorder by animateColorAsState(
                                    targetValue = if (isSelected) NimeCopper else NimeBorderHairline,
                                    animationSpec = tween(180),
                                    label = "pad_border_${preset.id}"
                                )

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = padBg,
                                    border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, padBorder),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("preset_${preset.id}")
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectPreset(preset) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) NimeCopper else NimeTextMuted)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = preset.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isSelected) NimeCopperSoft else NimeTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Single Unambiguous Primary Trigger (Constrained by isMoodValid)
            item {
                Button(
                    onClick = { onTuneAndPlay(state.moodInput) },
                    enabled = isMoodValid && !state.isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .testTag("btn_enter_play_surface"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NimeCopper,
                        contentColor = NimeObsidianBg,
                        disabledContainerColor = NimeRecessedSurface,
                        disabledContentColor = NimeTextMuted
                    ),
                    border = if (!isMoodValid && !state.isGenerating) {
                        BorderStroke(1.dp, NimeBorderHairline)
                    } else null
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        when {
                            state.isGenerating -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = NimeCopper,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "TUNING INSTRUMENT...",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = NimeCopper
                                )
                            }
                            !isMoodValid -> {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked until a mood is entered",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ENTER A MOOD TO PLAY",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "SYNTHESIZE & PLAY",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        // Multi-Stage Synthesis Progress Overlay
        AnimatedVisibility(
            visible = state.isGenerating,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NimeObsidianBg.copy(alpha = 0.90f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = NimePanelSurface),
                    border = BorderStroke(1.dp, NimeCopper.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(NimeRecessedSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { state.generationProgress },
                                modifier = Modifier.size(72.dp),
                                color = NimeCopper,
                                trackColor = NimeBorderHairline,
                                strokeWidth = 4.dp
                            )
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Synthesizing Sound World",
                                tint = NimeCopper,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Text(
                            text = "TUNING \"${state.moodInput.uppercase()}\"",
                            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 1.sp),
                            color = NimeCopperSoft
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // 4-Stage Parametric Synthesis Pipeline Visibility
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StagePill("1. Connect", active = state.generationStage >= 1, modifier = Modifier.weight(1f))
                            StagePill("2. AI Design", active = state.generationStage >= 2, modifier = Modifier.weight(1f))
                            StagePill("3. Patch", active = state.generationStage >= 3, modifier = Modifier.weight(1f))
                            StagePill("4. Ready", active = state.generationStage >= 4, modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 10-Note Multisample Palette Bars
                        val paletteColors = listOf(NimeCopper, NimeCopperSoft, NimeSage, NimeCobalt, NimeBrassGold)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.height(24.dp)
                        ) {
                            repeat(10) { index ->
                                val barHeight = 6.dp + ((index * 4 + (state.generationProgress * 48).toInt()) % 18).dp
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(barHeight)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(paletteColors[index % paletteColors.size])
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        LinearProgressIndicator(
                            progress = { state.generationProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = NimeCopper,
                            trackColor = NimeRecessedSurface
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.generationStatusText.ifBlank { "Preparing synthesis..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = NimeTextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${(state.generationProgress * 100).toInt()}%",
                                style = NimeMonoTelemetry.copy(fontWeight = FontWeight.Bold),
                                color = NimeCopperSoft
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        OutlinedButton(
                            onClick = onAbortGeneration,
                            enabled = !state.isAborting,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                1.dp,
                                if (state.isAborting) NimeBorderHairline else NimeCrimson.copy(alpha = 0.7f)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = NimeCrimson,
                                disabledContentColor = NimeTextMuted
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("btn_abort_generation")
                        ) {
                            if (state.isAborting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NimeTextMuted,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "CANCELLING...",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel generation",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "CANCEL GENERATION",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        // Backend Connection Error Dialog
        if (state.isBackendErrorDialogShown) {
            AlertDialog(
                onDismissRequest = onDismissError,
                icon = {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = NimeCrimson
                    )
                },
                title = {
                    Text(
                        text = "Generator Unreachable",
                        style = MaterialTheme.typography.titleMedium,
                        color = NimeTextPrimary
                    )
                },
                text = {
                    Text(
                        text = state.backendErrorMessage ?: "Could not connect to the online sound generator.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NimeTextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NimeCopper,
                            contentColor = NimeObsidianBg
                        )
                    ) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = NimeTextSecondary)
                    }
                },
                containerColor = NimePanelSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // Backend Server URL Configuration Dialog
        if (showServerDialog) {
            AlertDialog(
                onDismissRequest = { showServerDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = null,
                        tint = NimeCobalt
                    )
                },
                title = {
                    Text(
                        text = "Backend Server URL",
                        style = MaterialTheme.typography.titleMedium,
                        color = NimeTextPrimary
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Public ngrok or FastAPI server URL. Falls back to the local parametric synth automatically if unreachable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = NimeTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serverUrlDraft,
                            onValueChange = { serverUrlDraft = it },
                            placeholder = {
                                Text("https://...ngrok-free.dev/", color = NimeTextMuted)
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = NimeRecessedSurface,
                                unfocusedContainerColor = NimeRecessedSurface,
                                focusedBorderColor = NimeCobalt,
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
                            onUpdateBackendUrl(serverUrlDraft)
                            showServerDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NimeCopper,
                            contentColor = NimeObsidianBg
                        )
                    ) {
                        Text("Save URL", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showServerDialog = false }) {
                        Text("Cancel", color = NimeTextSecondary)
                    }
                },
                containerColor = NimePanelSurface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
private fun StagePill(
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (active) NimeCopper.copy(alpha = 0.18f) else NimeRecessedSurface,
        border = BorderStroke(1.dp, if (active) NimeCopper else NimeBorderHairline)
    ) {
        Text(
            text = label,
            style = NimeMonoTelemetry.copy(fontSize = 10.sp),
            color = if (active) NimeCopperSoft else NimeTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
        )
    }
}
