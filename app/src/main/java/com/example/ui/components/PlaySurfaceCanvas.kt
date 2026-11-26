package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.NimePureDataEngine
import com.example.model.ScaleNote
import com.example.model.TouchPoint
import com.example.ui.theme.NimeBorderHairline
import com.example.ui.theme.NimeBrassGold
import com.example.ui.theme.NimeCobalt
import com.example.ui.theme.NimeCopper
import com.example.ui.theme.NimeCopperSoft
import com.example.ui.theme.NimeMonoTelemetry
import com.example.ui.theme.NimeSage
import com.example.ui.theme.NimeTextPrimary
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

internal fun noteAccentColorForIndex(index: Int): Color {
    val base = when (index % 5) {
        0 -> NimeCopper
        1 -> NimeCopperSoft
        2 -> NimeSage
        3 -> NimeCobalt
        else -> NimeBrassGold
    }
    return if (index >= 5) lerp(base, NimeTextPrimary, 0.14f) else base
}

/**
 * Continuous 2D Parametric Play Surface Canvas (Obsidian Acoustic Hardware Palette):
 * - X-axis: 10 seamless pitch zones dynamically tuned to the active [ScaleNote] bank
 * - Y-axis: per-voice filter expression (`OPEN` at top -> `WARM` at bottom)
 * - Tilt-reactive horizon line (reflecting effective reverb wet mix) + audio-coherent oscilloscope
 * - Polyphonic touch reticles showing active glow per finger
 */
@Composable
fun PlaySurfaceCanvas(
    scaleNotes: List<ScaleNote>,
    activeTouches: List<TouchPoint>,
    tiltZ: Float,
    effectiveReverbWet: Float,
    wavePhase: Float,
    pulsePhase: Float,
    readWaveform: (FloatArray) -> Float = { 0f },
    modifier: Modifier = Modifier
) {
    val smoothTilt by animateFloatAsState(
        targetValue = tiltZ.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "smooth_tilt"
    )
    val smoothReverb by animateFloatAsState(
        targetValue = effectiveReverbWet.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "smooth_reverb"
    )

    val latestReadWaveform by rememberUpdatedState(readWaveform)
    val scopeInput = remember { FloatArray(NimePureDataEngine.SCOPE_SAMPLE_COUNT) }
    val smoothedScope = remember { FloatArray(NimePureDataEngine.SCOPE_SAMPLE_COUNT) }
    var smoothedRms by remember { mutableFloatStateOf(0f) }
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { frameTime ->
                val rms = latestReadWaveform(scopeInput)
                smoothedRms += (rms - smoothedRms) * 0.25f
                for (i in smoothedScope.indices) {
                    smoothedScope[i] += (scopeInput[i] - smoothedScope[i]) * 0.38f
                }
                frameTick = frameTime
            }
        }
    }

    val wavePath = remember { Path() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE")
            val tick = frameTick

            val width = size.width
            val height = size.height
            val hasActiveMusic = activeTouches.isNotEmpty() || smoothedRms > 0.004f

            val tiltBias = (smoothTilt - 0.5f) * 2f // -1.0 .. +1.0
            val tiltMagnitude = abs(tiltBias)
            val tiltColor = lerp(NimeCopper, NimeCobalt, smoothReverb)

            // 0. Vertical Filter Atmosphere (Top = Open/Bright, Bottom = Warm/Filtered)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        NimeCobalt.copy(alpha = 0.07f),
                        Color.Transparent,
                        NimeCopper.copy(alpha = 0.05f)
                    )
                ),
                size = Size(width, height)
            )

            // Subtle Tilt + Reverb Atmospheric Field
            val atmosCenter = Offset(
                x = width * (0.5f + tiltBias * 0.30f),
                y = height * (0.5f - tiltMagnitude * 0.08f)
            )
            val atmosRadius = width.coerceAtLeast(height) * (0.58f + smoothReverb * 0.16f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        tiltColor.copy(
                            alpha = if (hasActiveMusic) {
                                0.04f + smoothReverb * 0.08f
                            } else {
                                0.02f + smoothReverb * 0.05f
                            }
                        ),
                        Color.Transparent
                    ),
                    center = atmosCenter,
                    radius = atmosRadius
                ),
                radius = atmosRadius,
                center = atmosCenter
            )

            // 1. Continuous Sound Field (no vertical keyboard dividers)

            // 2. Quiet Tilted Horizon Reference Line (parallels phone tilt & effective reverb depth)
            val centerY = height * 0.50f
            val visualTiltBias = (tiltBias * 1.35f).coerceIn(-1f, 1f)
            val tiltSlopeY = visualTiltBias * (height * 0.28f)
            val horizonLeftY = (centerY - tiltSlopeY).coerceIn(height * 0.16f, height * 0.84f)
            val horizonRightY = (centerY + tiltSlopeY).coerceIn(height * 0.16f, height * 0.84f)

            drawLine(
                color = lerp(NimeBorderHairline, tiltColor, 0.25f + smoothReverb * 0.28f).copy(alpha = 0.28f),
                start = Offset(0f, horizonLeftY),
                end = Offset(width, horizonRightY),
                strokeWidth = 0.8.dp.toPx()
            )

            // 3. Audio-Coherent Oscilloscope Wave with Layered Glow
            val primaryColor = activeTouches.lastOrNull()?.let { noteAccentColorForIndex(it.activeNote.index) } ?: tiltColor
            val waveColor = if (hasActiveMusic) {
                lerp(primaryColor, tiltColor, 0.35f)
            } else {
                tiltColor
            }

            val count = smoothedScope.size
            val maxWaveHeight = height * 0.24f
            val idleAmp = 3.dp.toPx()
            val energy = (smoothedRms * 2.4f).coerceIn(0f, 1f)
            wavePath.reset()

            for (i in 0 until count) {
                val t = i.toFloat() / (count - 1).coerceAtLeast(1)
                val x = t * width
                val baselineY = horizonLeftY + (horizonRightY - horizonLeftY) * t
                val edgeTaper = sin(PI * t).toFloat().coerceIn(0f, 1f).pow(0.45f)

                val audioDisplacement = smoothedScope[i] * maxWaveHeight * edgeTaper
                val idleDisplacement = sin(t * 2.0 * PI * 1.5 + wavePhase).toFloat() *
                    idleAmp * (1f - energy) * edgeTaper
                val y = baselineY + audioDisplacement + idleDisplacement

                if (i == 0) {
                    wavePath.moveTo(x, y)
                } else {
                    wavePath.lineTo(x, y)
                }
            }

            val outerGlowAlpha = if (hasActiveMusic) (0.06f + energy * 0.08f) else 0.03f
            val midGlowAlpha = if (hasActiveMusic) (0.14f + energy * 0.16f) else 0.07f
            val coreAlpha = if (hasActiveMusic) (0.50f + energy * 0.36f) else 0.18f

            drawPath(
                path = wavePath,
                color = waveColor.copy(alpha = outerGlowAlpha),
                style = Stroke(
                    width = (8.dp + 5.dp * energy).toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = wavePath,
                color = waveColor.copy(alpha = midGlowAlpha),
                style = Stroke(
                    width = (3.8.dp + 2.dp * energy).toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
            drawPath(
                path = wavePath,
                color = lerp(waveColor, NimeTextPrimary, if (hasActiveMusic) 0.25f * energy else 0f)
                    .copy(alpha = coreAlpha),
                style = Stroke(
                    width = if (hasActiveMusic) (1.3.dp + 0.5.dp * energy).toPx() else 0.9.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )

            // 4. Polyphonic Touch Reticles
            val reticleReach = 42.dp.toPx()
            activeTouches.forEach { point ->
                val centerOffset = Offset(point.x * width, point.y * height)
                val noteColor = noteAccentColorForIndex(point.activeNote.index)
                val brightness = point.filterBrightness

                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            noteColor.copy(alpha = 0.28f + brightness * 0.15f),
                            Color.Transparent
                        ),
                        startY = centerOffset.y - reticleReach,
                        endY = centerOffset.y + reticleReach
                    ),
                    start = Offset(centerOffset.x, centerOffset.y - reticleReach),
                    end = Offset(centerOffset.x, centerOffset.y + reticleReach),
                    strokeWidth = 0.8.dp.toPx()
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            noteColor.copy(alpha = 0.28f + brightness * 0.15f),
                            Color.Transparent
                        ),
                        startX = centerOffset.x - reticleReach,
                        endX = centerOffset.x + reticleReach
                    ),
                    start = Offset(centerOffset.x - reticleReach, centerOffset.y),
                    end = Offset(centerOffset.x + reticleReach, centerOffset.y),
                    strokeWidth = 0.8.dp.toPx()
                )

                val glowRadius = (28.dp + 18.dp * brightness).toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            noteColor.copy(alpha = 0.18f + brightness * 0.14f),
                            Color.Transparent
                        ),
                        center = centerOffset,
                        radius = glowRadius
                    ),
                    radius = glowRadius,
                    center = centerOffset
                )

                val rippleRadius = 14.dp.toPx() + pulsePhase * 24.dp.toPx()
                val rippleAlpha = (1f - pulsePhase) * 0.36f
                drawCircle(
                    color = noteColor.copy(alpha = rippleAlpha),
                    radius = rippleRadius,
                    center = centerOffset,
                    style = Stroke(width = 1.dp.toPx())
                )

                drawCircle(
                    color = noteColor.copy(alpha = 0.88f),
                    radius = 13.dp.toPx(),
                    center = centerOffset,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                val innerRadius = 4.5.dp.toPx() + 0.6.dp.toPx() * sin(wavePhase * 2f)
                drawCircle(
                    color = NimeTextPrimary.copy(alpha = 0.94f),
                    radius = innerRadius,
                    center = centerOffset
                )
            }
        }

        // Subtle Y-Axis Filter Orientation Hints (Left Edge)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .padding(start = 10.dp, top = 20.dp, bottom = 78.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "OPEN",
                style = NimeMonoTelemetry.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                color = NimeCobalt.copy(alpha = 0.55f)
            )
            Text(
                text = "WARM",
                style = NimeMonoTelemetry.copy(fontSize = 9.sp, letterSpacing = 0.8.sp),
                color = NimeCopper.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
fun MiniEqualizer(
    isPlaying: Boolean,
    bounce: Float,
    color: Color = NimeSage,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val h1 = if (isPlaying) (5.dp + 7.dp * bounce) else 3.dp
        val h2 = if (isPlaying) (4.dp + 9.dp * (1f - bounce)) else 4.dp
        val h3 = if (isPlaying) (5.dp + 8.dp * ((bounce * 1.6f) % 1f)) else 2.dp

        Box(modifier = Modifier.width(2.5.dp).height(h1).clip(RoundedCornerShape(1.dp)).background(color))
        Box(modifier = Modifier.width(2.5.dp).height(h2).clip(RoundedCornerShape(1.dp)).background(color))
        Box(modifier = Modifier.width(2.5.dp).height(h3).clip(RoundedCornerShape(1.dp)).background(color))
    }
}
