package com.tablettouch.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Обёртка над MediaCodec (аппаратный H.264-декодер Android 12).
 * Кадры приходят от сервера как Annex-B потоки; на «restart» (смена ориентации
 * потока) декодер пересоздаётся — геометрия видео меняется на 90°.
 */
class H264Decoder(private val mime: String = "video/avc") {

    @Volatile private var codec: MediaCodec? = null
    private const val TAG = "TTDecode"

    /** Собираем PS из NAL-пакетов, чтобы не терять SPS/PPS между кадрами. */
    private fun feed(ps: ByteBuffer) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(20_000)
            if (idx < 0) return
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            ps.position(0)
            ib.put(ps)
            c.queueInputBuffer(idx, 0, ps.limit(), System.nanoTime() / 1000, 0)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "input buffer: ${e.message}")
        }
    }

    fun start(surface: Surface, width: Int, height: Int) {
        stop()
        val format = MediaFormat.createVideoFormat(mime, width, height)
        // профиль High + уровень 5.1 — запас под 1080p60
        format.setInteger(MediaFormat.KEY_PROFILE_LEVEL, 0x0200 /* High */)
        val c = MediaCodec.createDecoderByType(mime)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        Log.i(TAG, "decoder started ${width}x$height")
    }

    fun stop() {
        try {
            codec?.stop(); codec?.release()
        } catch (_: Exception) { }
        codec = null
    }

    /**
     * Подать один Access Unit (Annex-B, с кодами 00 00 01 / 00 00 00 01).
     * Вывод кадров асинхронный — они сразу рисуются на Surface.
     */
    fun queueFrame(au: ByteBuffer, ptsUs: Long) {
        val c = codec ?: return
        try {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx < 0) return
            val ib = c.getInputBuffer(idx) ?: return
            ib.clear()
            au.position(0)
            ib.put(au.array(), au.arrayOffset(), au.limit())
            c.queueInputBuffer(idx, 0, au.limit(), ptsUs, 0)
            drain(c)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queue: ${e.message}")
        }
    }

    fun drain() { codec?.let { drain(it) } }

    private fun drain(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            when {
                idx >= 0 -> c.releaseOutputBuffer(idx, true) // render=true → на Surface
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    Log.i(TAG, "out format: ${c.outputFormat}")
                else -> break // AGAIN / TRY_AGAIN_LATER
            }
        }
    }
}
