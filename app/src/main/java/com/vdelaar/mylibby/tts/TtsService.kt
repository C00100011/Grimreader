package com.vdelaar.mylibby.tts

import android.app.PendingIntent
import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vdelaar.mylibby.MainActivity
import com.vdelaar.mylibby.MyLibbyApp

/** Exposes read-aloud playback to the lock screen, notification and Bluetooth buttons. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class TtsService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: TtsPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as MyLibbyApp).container.tts
        val p = TtsPlayer(Looper.getMainLooper(), controller)
        player = p
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p).setSessionActivity(intent).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as MyLibbyApp).container.tts.requestStop()
        stopSelf()
    }

    override fun onDestroy() {
        player?.release()
        session?.release()
        session = null
        super.onDestroy()
    }
}

@UnstableApi
private class TtsPlayer(looper: Looper, private val controller: TtsController) : SimpleBasePlayer(looper) {

    private val listener: () -> Unit = { invalidateState() }

    init {
        controller.addStateListener(listener)
    }

    override fun getState(): State {
        val s = controller.state.value
        val active = s.status != TtsStatus.IDLE
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_GET_TIMELINE,
                    )
                    .build()
            )
            .setPlayWhenReady(s.status == TtsStatus.PLAYING, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (active) {
            val metadata = MediaMetadata.Builder()
                .setTitle(s.bookTitle.ifBlank { com.vdelaar.mylibby.core.str(com.vdelaar.mylibby.R.string.reading_aloud) })
                .setArtist(s.chapter)
                .setDisplayTitle(s.bookTitle)
                .build()
            builder
                .setPlaylist(
                    listOf(
                        MediaItemData.Builder("tts")
                            .setMediaItem(MediaItem.Builder().setMediaId("tts").setMediaMetadata(metadata).build())
                            .setMediaMetadata(metadata)
                            .build()
                    )
                )
                .setPlaybackState(Player.STATE_READY)
        } else {
            builder.setPlaybackState(Player.STATE_IDLE)
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        val playing = controller.state.value.status == TtsStatus.PLAYING
        if (playWhenReady != playing) controller.requestPlayPause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.requestStop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        controller.removeStateListener(listener)
        return Futures.immediateVoidFuture()
    }
}
