package com.familink.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** P2 renderer for game-only frames/audio received over the peer data channel. */
class GameStreamView(context: Context) : View(context) {
    @Volatile private var frame: Bitmap? = null
    @Volatile var statusText: String = "Aguardando o jogo do P1…"
    private val paint = Paint().apply { isFilterBitmap = false }
    private val target = RectF()
    private val audioQueue = LinkedBlockingQueue<AudioChunk>(32)
    private val audioRunning = AtomicBoolean(true)
    @Volatile private var track: AudioTrack? = null
    @Volatile private var audioThread: Thread? = null
    private data class AudioChunk(val data: ByteArray, val sampleRate: Int)

    init {
        setBackgroundColor(Color.BLACK)
        audioThread = Thread({ audioLoop() }, "FamiLink-stream-audio").apply { isDaemon = true; start() }
    }

    fun accept(kind: Int, payload: ByteArray, sampleRate: Int) {
        when (kind) {
            1 -> {
                val decoded = BitmapFactory.decodeByteArray(payload, 0, payload.size) ?: return
                post {
                    val old = frame
                    frame = decoded
                    if (old !== decoded) old?.recycle()
                    statusText = "Recebendo jogo ao vivo do P1"
                    invalidate()
                }
            }
            2 -> {
                if (sampleRate !in 8000..192000 || payload.isEmpty()) return
                if (audioQueue.remainingCapacity() == 0) audioQueue.poll()
                audioQueue.offer(AudioChunk(payload, sampleRate))
            }
        }
    }

    private fun audioLoop() {
        var activeRate = 0
        while (audioRunning.get()) {
            try {
                val chunk = audioQueue.take()
                if (track == null || activeRate != chunk.sampleRate) {
                    try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
                    activeRate = chunk.sampleRate
                    val minBuffer = AudioTrack.getMinBufferSize(activeRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(activeRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setBufferSizeInBytes(maxOf(minBuffer, chunk.data.size * 4)).build().also { it.play() }
                }
                track?.write(chunk.data, 0, chunk.data.size, AudioTrack.WRITE_NON_BLOCKING)
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            catch (_: Exception) { track = null }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val current = frame
        if (current != null && width > 0 && height > 0) {
            val scale = minOf(width.toFloat() / current.width, height.toFloat() / current.height)
            val w = current.width * scale; val h = current.height * scale
            target.set((width-w)/2f, (height-h)/2f, (width+w)/2f, (height+h)/2f)
            canvas.drawBitmap(current, null, target, paint)
        } else {
            paint.color = Color.WHITE; paint.textSize = 28f
            canvas.drawText(statusText, 24f, height / 2f, paint)
        }
    }

    override fun onDetachedFromWindow() {
        audioRunning.set(false)
        audioThread?.interrupt(); audioThread = null
        try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        track = null
        frame?.recycle(); frame = null
        super.onDetachedFromWindow()
    }
}
