package com.example.pulsemusic

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class PlayerEngine(private val context: Context, private val repo: MusicRepository) {
    private var pending: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var tracks: Map<String, Song> = emptyMap()

    var current by mutableStateOf<Song?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeat by mutableStateOf(false)
        private set
    var position by mutableStateOf(0L)
        private set
    var duration by mutableStateOf(0L)
        private set
    var ready by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
        override fun onRepeatModeChanged(repeatMode: Int) { repeat = repeatMode != Player.REPEAT_MODE_OFF }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            current = mediaItem?.mediaId?.let(tracks::get) ?: current
            updatePosition()
        }
        override fun onPlaybackStateChanged(playbackState: Int) { updatePosition() }
        override fun onPlayerError(exception: androidx.media3.common.PlaybackException) {
            error = "Playback failed. Check connection or try another track."
        }
    }

    fun setTracks(songs: List<Song>) { tracks = songs.associateBy { it.id } }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        pending = future
        future.addListener({
            runCatching {
                val result = future.get()
                controller = result
                result.addListener(listener)
                ready = true
                isPlaying = result.isPlaying
                updatePosition()
            }.onFailure { error = "Could not connect to the playback service" }
        }, ContextCompat.getMainExecutor(context))
    }

    fun play(song: Song, list: List<Song>) {
        val player = controller ?: run { error = "Player is still connecting"; return }
        error = null
        setTracks(list)
        val items = list.map { entry ->
            MediaItem.Builder()
                .setMediaId(entry.id)
                .setUri(repo.playableUri(entry))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist).build())
                .build()
        }
        val index = list.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        player.setMediaItems(items, index, 0)
        player.prepare()
        player.play()
        current = song
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled; shuffle = it.shuffleModeEnabled } }
    fun toggleRepeat() { controller?.let { it.repeatMode = if (it.repeatMode == Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF; repeat = it.repeatMode != Player.REPEAT_MODE_OFF } }
    fun currentQueue(): List<Song> {
        val p = controller ?: return emptyList()
        return (0 until p.mediaItemCount).mapNotNull { index ->
            tracks[p.getMediaItemAt(index).mediaId]
        }
    }
    fun jumpTo(song: Song) {
        val p = controller ?: return
        val index = (0 until p.mediaItemCount).firstOrNull {
            p.getMediaItemAt(it).mediaId == song.id
        } ?: return
        p.seekTo(index, 0L)
        p.play()
    }
    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    fun seek(fraction: Float) {
        val d = controller?.duration ?: return
        if (d > 0) controller?.seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
    }
    fun updatePosition() {
        controller?.let {
            position = it.currentPosition.coerceAtLeast(0L)
            duration = it.duration.coerceAtLeast(0L)
            isPlaying = it.isPlaying
        }
    }
    fun dismissError() { error = null }
    fun release() {
        controller?.removeListener(listener)
        pending?.let { MediaController.releaseFuture(it) }
        controller = null
        ready = false
    }
}
