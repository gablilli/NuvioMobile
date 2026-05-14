package com.nuvio.app.features.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlin.math.roundToInt

@Composable
actual fun LockPlayerToLandscape() {
    val activity = LocalContext.current.findActivity() ?: return
    if (!activity.shouldForceLandscapePlayer()) return

    DisposableEffect(activity) {
        val previousOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            activity.requestedOrientation = previousOrientation
        }
    }
}

@Composable
actual fun EnterImmersivePlayerMode(keepScreenAwake: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return

    DisposableEffect(activity) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior

        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

@Composable
actual fun ManagePlayerPictureInPicture(
    isPlaying: Boolean,
    playerSize: IntSize,
) {
    val activity = LocalContext.current.findActivity() ?: return

    DisposableEffect(activity) {
        onDispose {
            PlayerPictureInPictureManager.clearSession(activity)
        }
    }

    SideEffect {
        PlayerPictureInPictureManager.updateSession(
            activity = activity,
            isActive = true,
            isPlaying = isPlaying,
            playerSize = playerSize,
        )
    }
}

@Composable
actual fun rememberPlayerGestureController(): PlayerGestureController? {
    val context = LocalContext.current
    val activity = context.findActivity() ?: return null
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null

    val controller = remember(activity, audioManager) {
        AndroidPlayerGestureController(
            activity = activity,
            audioManager = audioManager,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.restoreBrightness()
        }
    }

    return controller
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.shouldForceLandscapePlayer(): Boolean {
    if (resources.configuration.smallestScreenWidthDp >= 600) return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode) return false
    return true
}

private class AndroidPlayerGestureController(
    private val activity: Activity,
    private val audioManager: AudioManager,
) : PlayerGestureController {
    private val originalBrightness = activity.window.attributes.screenBrightness
    private var brightnessRestored = false

    override fun currentBrightness(): Float {
        val windowValue = activity.window.attributes.screenBrightness
        return if (windowValue in 0f..1f) {
            windowValue.coerceIn(0.02f, 1f)
        } else {
            readSystemBrightness()
        }
    }

    override fun setBrightness(level: Float): Float {
        val target = level.coerceIn(0.02f, 1f)
        val attributes = activity.window.attributes
        attributes.screenBrightness = target
        activity.window.attributes = attributes
        return target
    }

    override fun currentVolume(): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, maxVolume)
        val fraction = currentVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = currentVolume == 0,
        )
    }

    override fun setVolume(level: Float): PlayerAudioLevel {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())
            .roundToInt()
            .coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        val fraction = targetVolume.toFloat() / maxVolume.toFloat()
        return PlayerAudioLevel(
            fraction = fraction,
            isMuted = targetVolume == 0,
        )
    }

    fun restoreBrightness() {
        if (brightnessRestored) return
        brightnessRestored = true

        val attributes = activity.window.attributes
        attributes.screenBrightness = when {
            originalBrightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else -> originalBrightness.coerceIn(0f, 1f)
        }
        activity.window.attributes = attributes
    }

    private fun readSystemBrightness(): Float =
        runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
        }.getOrDefault(127)
            .coerceIn(1, 255)
            .toFloat() / 255f
}

@Composable
actual fun rememberCastLauncher(): (() -> Unit)? {
    val activity = LocalContext.current.findActivity() as? AppCompatActivity ?: return null
    return remember(activity) {
        {
            AndroidCastPlaybackCoordinator.openChooser(activity)
        }
    }
}

actual fun prepareCastPlaybackRequest(request: ExternalPlayerPlaybackRequest) {
    AndroidCastPlaybackCoordinator.updatePendingRequest(request)
}

private object AndroidCastPlaybackCoordinator {
    private val stateLock = Any()
    private var pendingRequest: ExternalPlayerPlaybackRequest? = null
    private var sessionListenerRegistered = false
    private var listenerCastContext: CastContext? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            loadPendingRequest(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            unregisterSessionListenerIfIdle()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            unregisterSessionListenerIfIdle()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            loadPendingRequest(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            unregisterSessionListenerIfIdle()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    fun updatePendingRequest(request: ExternalPlayerPlaybackRequest) {
        synchronized(stateLock) {
            pendingRequest = request
        }
    }

    fun openChooser(activity: AppCompatActivity) {
        runCatching { CastContext.getSharedInstance(activity) }
            .getOrNull()
            ?.let { castContext ->
                ensureSessionListener(castContext)
                castContext.sessionManager.currentCastSession?.let(::loadPendingRequest)
                MediaRouteChooserDialog(activity).apply {
                    routeSelector = castContext.mergedSelector ?: MediaRouteSelector.EMPTY
                }.show()
            }
    }

    private fun ensureSessionListener(castContext: CastContext) {
        synchronized(stateLock) {
            if (sessionListenerRegistered) return
            castContext.sessionManager.addSessionManagerListener(
                sessionListener,
                CastSession::class.java,
            )
            sessionListenerRegistered = true
            listenerCastContext = castContext
        }
    }

    private fun unregisterSessionListenerIfIdle() {
        val castContextToUnregister = synchronized(stateLock) {
            if (pendingRequest != null || !sessionListenerRegistered) {
                null
            } else {
                val castContext = listenerCastContext
                listenerCastContext = null
                sessionListenerRegistered = false
                castContext
            }
        }
        castContextToUnregister?.sessionManager?.removeSessionManagerListener(
            sessionListener,
            CastSession::class.java,
        )
    }

    private fun loadPendingRequest(session: CastSession) {
        val request = synchronized(stateLock) { pendingRequest } ?: return
        val remoteMediaClient = session.remoteMediaClient ?: return
        val displayTitle = request.streamTitle?.takeIf { it.isNotBlank() } ?: request.title
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, displayTitle)
        }
        val mediaInfo = MediaInfo.Builder(request.sourceUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(request.castContentType())
            .setMetadata(metadata)
            .build()
        remoteMediaClient.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build(),
        )
        synchronized(stateLock) {
            pendingRequest = null
        }
        unregisterSessionListenerIfIdle()
    }
}

private fun ExternalPlayerPlaybackRequest.castContentType(): String {
    val headerType = sourceHeaders.entries.find { (key, _) ->
        key.equals("Content-Type", ignoreCase = true)
    }?.value?.substringBefore(';')?.trim()
    if (!headerType.isNullOrBlank()) return headerType
    return sourceUrl.castContentTypeFromUrl()
}

private fun String.castContentTypeFromUrl(): String {
    val normalized = substringBefore('?').substringBefore('#').lowercase()
    return when {
        normalized.endsWith(".m3u8") -> "application/x-mpegURL"
        normalized.endsWith(".mpd") -> "application/dash+xml"
        normalized.endsWith(".mkv") -> "video/x-matroska"
        normalized.endsWith(".webm") -> "video/webm"
        normalized.endsWith(".avi") -> "video/x-msvideo"
        normalized.endsWith(".mov") -> "video/quicktime"
        else -> "video/*"
    }
}
