package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntSize

interface PlayerGestureController {
    fun currentBrightness(): Float?
    fun setBrightness(level: Float): Float?
    fun currentVolume(): PlayerAudioLevel?
    fun setVolume(level: Float): PlayerAudioLevel?
}

data class PlayerAudioLevel(
    val fraction: Float,
    val isMuted: Boolean,
)

data class CastSessionSnapshot(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
)

@Composable
expect fun LockPlayerToLandscape()

@Composable
expect fun EnterImmersivePlayerMode(keepScreenAwake: Boolean)

@Composable
expect fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
)

@Composable
expect fun rememberPlayerGestureController(): PlayerGestureController?

@Composable
expect fun rememberCastLauncher(): (() -> Unit)?

expect fun prepareCastPlaybackRequest(request: ExternalPlayerPlaybackRequest)

@Composable
expect fun rememberCastSessionSnapshot(): CastSessionSnapshot

expect fun syncCastPlaybackRequestIfConnected(request: ExternalPlayerPlaybackRequest)

expect fun toggleCastPlayback()

expect fun seekCastBy(offsetMs: Long)

expect fun seekCastTo(positionMs: Long)
