package com.familink.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.View

/** Software Canvas renderer: avoids SurfaceView being hidden by Compose layers on older Android. */
class EmulatorSurfaceView(context: Context) : View(context) {
    private val frameLock = Any()
    private var frameBitmap: Bitmap? = null
    @Volatile private var running = false
    private var renderThread: Thread? = null
    private val pixels = IntArray(640 * 480)
    private val audio = ShortArray(4096)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val source = Rect()

    @Volatile var p1Mask: Int = 0
    @Volatile var p2Mask: Int = 0
    @Volatile var p1AnalogX: Float = 0f
    @Volatile var p1AnalogY: Float = 0f
    @Volatile var p2AnalogX: Float = 0f
    @Volatile var p2AnalogY: Float = 0f
    @Volatile var targetFps: Int = 30

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
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(16384).build()
            track.play()
            while (running) {
                val frameStart = System.nanoTime()
                val packed = NativeCoreBridge.nativeRunFrame(
                    p1Mask, p2Mask,
                    p1AnalogX, p1AnalogY, p2AnalogX, p2AnalogY,
                    pixels
                )
                val width = (packed ushr 16).coerceIn(0, 640)
                val height = (packed and 0xFFFF).coerceIn(0, 480)
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
                val samples = NativeCoreBridge.nativeReadAudio(audio)
                if (samples > 0) track.write(audio, 0, samples)
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
            // Center-crop the console frame into the whole game viewport: no letterbox bars.
            val viewRatio = width.toFloat() / height.toFloat()
            val frameRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            if (frameRatio > viewRatio) {
                val cropWidth = bitmap.height * viewRatio
                val left = (bitmap.width - cropWidth) / 2f
                source.set(left.toInt().coerceIn(0, bitmap.width - 1), 0, (left + cropWidth).toInt().coerceIn(1, bitmap.width), bitmap.height)
            } else {
                val cropHeight = bitmap.width / viewRatio
                val top = (bitmap.height - cropHeight) / 2f
                source.set(0, top.toInt().coerceIn(0, bitmap.height - 1), bitmap.width, (top + cropHeight).toInt().coerceIn(1, bitmap.height))
            }
            target.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(bitmap, source, target, paint)
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
