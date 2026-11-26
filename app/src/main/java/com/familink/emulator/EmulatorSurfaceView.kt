package com.familink.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View
import android.os.Process

/** Software Canvas renderer: avoids SurfaceView being hidden by Compose layers on older Android. */
class EmulatorSurfaceView(context: Context) : View(context) {
    private val frameLock = Any()
    private var frameBitmap: Bitmap? = null
    @Volatile private var running = false
    private var renderThread: Thread? = null
    // Several cores (especially interlaced Mega Drive modes) can emit frames larger than 640x480.
    private val pixels = IntArray(1024 * 768)
    private val audio = ShortArray(4096)
    private val pendingAudio = ShortArray(16384)
    private var pendingHead = 0
    private var pendingCount = 0
    // Nearest-neighbor scaling is both sharper for pixel art and cheaper on older phones.
    private val paint = Paint().apply { isFilterBitmap = false }
    private val target = RectF()

    @Volatile var p1Mask: Int = 0
    @Volatile var p2Mask: Int = 0
    @Volatile var p1AnalogX: Float = 0f
    @Volatile var p1AnalogY: Float = 0f
    @Volatile var p2AnalogX: Float = 0f
    @Volatile var p2AnalogY: Float = 0f
    @Volatile var targetFps: Int = 60

    init { setBackgroundColor(Color.BLACK); isFocusable = true }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) return
        running = true
        renderThread = Thread({ renderLoop() }, "FamiLink-native-render").apply { isDaemon = true; start() }
    }

    private fun renderLoop() {
        var track: AudioTrack? = null
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
            // Audio setup is optional: a legacy device's AudioTrack failure must never stop video rendering.
            track = try {
                val sampleRate = NativeCoreBridge.nativeGetAudioSampleRate().coerceIn(8000, 192000)
                val minimumBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(minimumBuffer).build().also { it.play() }
            } catch (_: Exception) { null }
            while (running) {
                val frameStart = System.nanoTime()
                val packed = NativeCoreBridge.nativeRunFrame(
                    p1Mask, p2Mask,
                    p1AnalogX, p1AnalogY, p2AnalogX, p2AnalogY,
                    pixels
                )
                val width = (packed ushr 16).coerceIn(0, 1024)
                val height = (packed and 0xFFFF).coerceIn(0, 768)
                if (width > 0 && height > 0) {
                    synchronized(frameLock) {
                        var bitmap = frameBitmap
                        if (bitmap == null || bitmap.width != width || bitmap.height != height) {
                            bitmap?.recycle()
                            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            frameBitmap = bitmap
                        }
                        bitmap!!.setPixels(pixels, 0, width, 0, 0, width, height)
                    }
                    postInvalidateOnAnimation()
                }
                // Feed native core audio without allowing an AudioTrack write to stall video frames.
                val incoming = NativeCoreBridge.nativeReadAudio(audio).coerceIn(0, audio.size)
                if (track != null && incoming > 0) {
                    if (pendingCount + incoming > pendingAudio.size) {
                        val drop = pendingCount + incoming - pendingAudio.size
                        pendingHead = (pendingHead + drop).mod(pendingAudio.size)
                        pendingCount -= drop
                    }
                    var tail = (pendingHead + pendingCount).mod(pendingAudio.size)
                    var copied = 0
                    while (copied < incoming) {
                        val chunk = minOf(incoming - copied, pendingAudio.size - tail)
                        System.arraycopy(audio, copied, pendingAudio, tail, chunk)
                        copied += chunk
                        tail = (tail + chunk).mod(pendingAudio.size)
                    }
                    pendingCount += incoming
                }
                if (pendingCount > 0) {
                    val contiguous = minOf(pendingCount, pendingAudio.size - pendingHead)
                    val written = track?.write(pendingAudio, pendingHead, contiguous, AudioTrack.WRITE_NON_BLOCKING) ?: 0
                    if (written > 0) {
                        pendingHead = (pendingHead + written).mod(pendingAudio.size)
                        pendingCount -= written
                    }
                }
                val budget = if (targetFps >= 60) 16_666_667L else 33_333_333L
                val remain = budget - (System.nanoTime() - frameStart)
                if (remain > 0) Thread.sleep(remain / 1_000_000, (remain % 1_000_000).toInt())
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // The activity's ROM status remains visible; never crash the UI thread.
        } finally {
            try { track?.pause(); track?.flush(); track?.release() } catch (_: Exception) { }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        synchronized(frameLock) {
            val bitmap = frameBitmap ?: return
            if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return
            // Fit the complete game frame into the available phone viewport.
            // Preserve the original aspect ratio: never crop or stretch the game image.
            val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val drawWidth = bitmap.width * scale
            val drawHeight = bitmap.height * scale
            val left = (width - drawWidth) / 2f
            val top = (height - drawHeight) / 2f
            target.set(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, target, paint)
        }
    }

    override fun onDetachedFromWindow() {
        running = false
        renderThread?.interrupt()
        try { renderThread?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        renderThread = null
        synchronized(frameLock) { frameBitmap?.recycle(); frameBitmap = null }
        super.onDetachedFromWindow()
    }
}
