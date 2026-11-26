package com.familink.emulator

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.SurfaceHolder
import android.view.SurfaceView

class EmulatorSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var running = false
    private var renderThread: Thread? = null
    private val pixelBuf = IntArray(640 * 480)
    private val audioBuf = ShortArray(4096)
    private val paint = Paint().apply { isFilterBitmap = false }

    @Volatile var p1Mask: Int = 0
    @Volatile var p2Mask: Int = 0
    @Volatile var targetFps: Int = 30

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        running = true
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(16384)
            .build()
        track.play()

        renderThread = Thread {
            var bmp = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)
            var frameTick = 0L
            while (running) {
                val frameStartMs = System.currentTimeMillis()
                val packedDim = NativeCoreBridge.nativeRunFrame(p1Mask, p2Mask, pixelBuf)
                frameTick++

                // Em modo 30 FPS (celular fraco), evita re-upload de bitmap nos quadros ímpares
                val shouldDraw = targetFps >= 60 || (frameTick % 2L == 0L)
                if (shouldDraw) {
                    val w = (packedDim ushr 16).coerceIn(160, 640)
                    val h = (packedDim and 0xFFFF).coerceIn(144, 480)
                    if (bmp.width != w || bmp.height != h) {
                        bmp.recycle()
                        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    }
                    bmp.setPixels(pixelBuf, 0, w, 0, 0, w, h)

                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            canvas.drawRGB(0, 0, 0)
                            val dst = Rect(0, 0, canvas.width, canvas.height)
                            canvas.drawBitmap(bmp, null, dst, paint)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }

                val samples = NativeCoreBridge.nativeReadAudio(audioBuf)
                if (samples > 0) {
                    track.write(audioBuf, 0, samples)
                }

                val frameBudgetMs = 16L
                val elapsed = System.currentTimeMillis() - frameStartMs
                if (elapsed < frameBudgetMs) {
                    Thread.sleep(frameBudgetMs - elapsed)
                }
            }
            track.stop()
            track.release()
            bmp.recycle()
        }.apply { start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        running = false
        renderThread?.join(500)
        renderThread = null
    }
}
