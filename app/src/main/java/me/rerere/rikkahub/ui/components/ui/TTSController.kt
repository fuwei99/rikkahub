package me.rerere.rikkahub.ui.components.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Forward02
import me.rerere.hugeicons.stroke.Pause
import me.rerere.hugeicons.stroke.Play
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.tts.model.PlaybackState
import me.rerere.tts.model.PlaybackStatus

@Composable
fun TTSController() {
    val ttsState = LocalTTSState.current
    val isSpeaking by ttsState.isSpeaking.collectAsState()
    val playbackState by ttsState.playbackState.collectAsState()
    val isPlayingCachedAudio by ttsState.isPlayingCachedAudio.collectAsState()
    var isVisible by remember { mutableStateOf(false) }
    var expand by remember { mutableStateOf(false) }

    LaunchedEffect(isSpeaking) {
        if (isSpeaking) isVisible = true
    }

    FloatingWindow(tag = "tts_controller", visibility = isVisible) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.padding(8.dp),
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayPauseButton(playbackState = playbackState, ttsState = ttsState)
                    IconButton(onClick = {
                        ttsState.stop()
                        isVisible = false
                    }) {
                        Icon(HugeIcons.Cancel01, contentDescription = null)
                    }
                    AnimatedVisibility(expand) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SpeedButton(playbackState, ttsState)
                            FastForwardButton(ttsState)
                        }
                    }
                    IconButton(onClick = { expand = !expand }) {
                        Icon(
                            if (expand) HugeIcons.ArrowLeft01 else HugeIcons.ArrowRight01,
                            contentDescription = null,
                        )
                    }
                }

                // A stream does not have a stable duration. Only completed local cache playback is seekable.
                AnimatedVisibility(visible = isPlayingCachedAudio && playbackState.durationMs > 0L) {
                    CachedAudioSeekBar(playbackState = playbackState, ttsState = ttsState)
                }
            }
        }
    }
}

@Composable
private fun CachedAudioSeekBar(playbackState: PlaybackState, ttsState: CustomTtsState) {
    val duration = playbackState.durationMs.coerceAtLeast(1L)
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }
    val displayedPosition = if (dragging) dragPosition else playbackState.positionMs.toFloat()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Slider(
            value = displayedPosition.coerceIn(0f, duration.toFloat()),
            onValueChange = {
                dragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                ttsState.seekTo(dragPosition.toLong())
                dragging = false
            },
            valueRange = 0f..duration.toFloat(),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(displayedPosition.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatPlaybackTime(duration), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = (milliseconds / 1_000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun FastForwardButton(ttsState: CustomTtsState) {
    IconButton(onClick = { ttsState.fastForward(5_000) }) {
        Icon(HugeIcons.Forward02, contentDescription = null)
    }
}

@Composable
private fun PlayPauseButton(playbackState: PlaybackState, ttsState: CustomTtsState) {
    FilledTonalIconButton(
        onClick = {
            if (playbackState.status == PlaybackStatus.Playing) ttsState.pause() else ttsState.resume()
        },
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Icon(
            imageVector = if (playbackState.status == PlaybackStatus.Playing) HugeIcons.Pause else HugeIcons.Play,
            contentDescription = null,
        )
        if (playbackState.status in setOf(PlaybackStatus.Playing, PlaybackStatus.Buffering, PlaybackStatus.Paused)) {
            CircularProgressIndicator(
                progress = {
                    if (playbackState.durationMs > 0L) {
                        playbackState.positionMs.toFloat() / playbackState.durationMs
                    } else {
                        0f
                    }
                },
                color = MaterialTheme.colorScheme.tertiary,
                strokeWidth = 2.dp,
                trackColor = Color.Transparent,
            )
        }
    }
}

@Composable
private fun SpeedButton(playbackState: PlaybackState, ttsState: CustomTtsState) {
    TextButton(
        onClick = {
            ttsState.setSpeed(
                when (playbackState.speed) {
                    0.8f -> 1.0f
                    1.0f -> 1.2f
                    1.2f -> 1.5f
                    else -> 0.8f
                },
            )
        },
    ) {
        Text(text = "x${"%.1f".format(playbackState.speed)}")
    }
}
