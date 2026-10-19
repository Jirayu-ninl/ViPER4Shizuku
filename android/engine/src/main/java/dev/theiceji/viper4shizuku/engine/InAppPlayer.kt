package dev.theiceji.viper4shizuku.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The in-app player: a plain media3 player whose decoded PCM goes through [dsp] before output.
 * Used as the DSP test harness and for playing local files. Must be used from the main thread.
 */
@UnstableApi
class InAppPlayer(
    context: Context,
    val dsp: NativeDsp,
) : AutoCloseable {
    data class State(
        val uri: Uri? = null,
        val title: String? = null,
        val isPlaying: Boolean = false,
        val error: String? = null,
    )

    private val processor = DspAudioProcessor(dsp)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    var bypass: Boolean
        get() = processor.bypass
        set(value) {
            processor.bypass = value
        }

    private val renderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink
                    .Builder(context)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
        }

    val player: ExoPlayer =
        ExoPlayer
            .Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .build()
            .also { p ->
                p.addListener(
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            _state.value = _state.value.copy(isPlaying = isPlaying)
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            _state.value = _state.value.copy(isPlaying = false, error = error.message)
                        }

                        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                            val title = mediaMetadata.title?.toString()
                            if (title != null) _state.value = _state.value.copy(title = title)
                        }
                    },
                )
            }

    fun play(
        uri: Uri,
        displayName: String? = null,
    ) {
        _state.value = State(uri = uri, title = displayName)
        player.setMediaItem(MediaItem.fromUri(uri))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.play()
    }

    fun togglePause() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        // After an error the player is idle and has to be prepared again.
        if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
            _state.value = _state.value.copy(error = null)
            player.prepare()
        }
        player.play()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _state.value = State()
    }

    override fun close() {
        player.release()
    }
}
