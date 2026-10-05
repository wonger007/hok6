package com.studybook.reader

import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.material.button.MaterialButton

private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)

/** Bottom player bar that drives [PlaybackService] through a [MediaController]. */
class AudioBar(
    activity: AppCompatActivity,
    private val onPlayingChanged: (Uri?) -> Unit,
) : Player.Listener {
    private val root: View = activity.findViewById(R.id.audio_bar)
    private val title: TextView = activity.findViewById(R.id.audio_title)
    private val time: TextView = activity.findViewById(R.id.audio_time)
    private val seek: SeekBar = activity.findViewById(R.id.audio_seek)
    private val play: ImageButton = activity.findViewById(R.id.audio_play)
    private val prev: ImageButton = activity.findViewById(R.id.audio_prev)
    private val next: ImageButton = activity.findViewById(R.id.audio_next)
    private val speed: MaterialButton = activity.findViewById(R.id.audio_speed)
    private val repeat: ImageButton = activity.findViewById(R.id.audio_repeat)

    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var pending: ((MediaController) -> Unit)? = null
    private var dragging = false
    private val future = MediaController.Builder(
        activity, SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
    ).buildAsync()

    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    init {
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(this)
            pending?.invoke(c)
            pending = null
            refresh()
        }, ContextCompat.getMainExecutor(activity))

        play.setOnClickListener {
            controller?.run {
                when {
                    playbackState == Player.STATE_ENDED -> { seekTo(0); play() }
                    playWhenReady -> pause()
                    else -> { if (playbackState == Player.STATE_IDLE) prepare(); play() }
                }
            }
        }
        prev.setOnClickListener { controller?.seekToPrevious() }
        next.setOnClickListener { controller?.seekToNextMediaItem() }
        activity.findViewById<View>(R.id.audio_back).setOnClickListener {
            controller?.run { seekTo((currentPosition - 5000).coerceAtLeast(0)) }
        }
        speed.setOnClickListener {
            controller?.run {
                val i = SPEEDS.indexOfFirst { it >= playbackParameters.speed - 0.01f }
                setPlaybackSpeed(SPEEDS[(i + 1) % SPEEDS.size])
            }
        }
        repeat.setOnClickListener {
            controller?.run {
                repeatMode = if (repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE
            }
        }
        activity.findViewById<View>(R.id.audio_close).setOnClickListener {
            controller?.run { stop(); clearMediaItems() }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) showTime(progress.toLong(), controller?.duration ?: 0)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                controller?.seekTo(bar.progress.toLong())
            }
        })
    }

    /** Plays every audio file of the chapter as a playlist, starting at [start]. */
    fun play(playlist: List<Entry>, start: Entry, album: String) {
        val action = { c: MediaController ->
            val items = playlist.map { e ->
                MediaItem.Builder()
                    .setUri(e.uri)
                    .setMediaId(e.uri.toString())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(e.name).setArtist(album).build())
                    .build()
            }
            c.setMediaItems(items, playlist.indexOf(start).coerceAtLeast(0), 0)
            c.prepare()
            c.play()
        }
        controller?.let(action) ?: run { pending = action }
    }

    fun release() {
        handler.removeCallbacks(ticker)
        controller?.removeListener(this)
        MediaController.releaseFuture(future)
    }

    override fun onEvents(player: Player, events: Player.Events) = refresh()

    private fun refresh() {
        val c = controller
        val active = c != null && c.mediaItemCount > 0
        root.isVisible = active
        handler.removeCallbacks(ticker)
        if (c == null || !active) {
            onPlayingChanged(null)
            return
        }
        title.text = c.mediaMetadata.title ?: ""
        val playing = c.playWhenReady && c.playbackState != Player.STATE_ENDED
        play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        prev.isEnabled = c.hasPreviousMediaItem() || c.currentPosition > 0
        next.isEnabled = c.hasNextMediaItem()
        next.alpha = if (next.isEnabled) 1f else 0.4f
        speed.text = "%s×".format(c.playbackParameters.speed.toString().removeSuffix(".0"))
        repeat.alpha = if (c.repeatMode == Player.REPEAT_MODE_ONE) 1f else 0.35f
        onPlayingChanged(c.currentMediaItem?.mediaId?.let(Uri::parse))
        updateProgress()
        handler.postDelayed(ticker, 500)
    }

    private fun updateProgress() {
        val c = controller ?: return
        val duration = c.duration.takeIf { it > 0 } ?: 0
        seek.max = duration.toInt()
        if (!dragging) {
            seek.progress = c.currentPosition.toInt()
            showTime(c.currentPosition, duration)
        }
    }

    private fun showTime(position: Long, duration: Long) {
        time.text = if (duration > 0) "${format(position)} / ${format(duration)}" else format(position)
    }

    private fun format(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
