package com.example.remotescreen

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Decodes H.264 stream from screenrecord to a Surface via MediaCodec.
 */
class VideoDecoder {

    companion object {
        private const val TAG = "RemoteScreen"
    }

    private var codec: MediaCodec? = null
    private var outputSurface: Surface? = null
    @Volatile private var running = false

    fun setSurface(surface: Surface) {
        outputSurface = surface
    }

    fun start(videoWidth: Int, videoHeight: Int) {
        val surface = outputSurface ?: throw IllegalStateException("Surface not set")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)

        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        running = true

        // Output rendering thread
        Thread {
            val info = MediaCodec.BufferInfo()
            while (running) {
                try {
                    val outIndex = c.dequeueOutputBuffer(info, 50_000)
                    if (outIndex >= 0) {
                        c.releaseOutputBuffer(outIndex, true)
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }.apply {
            name = "video-output"
            isDaemon = true
            start()
        }
    }

    fun feed(data: ByteArray, length: Int) {
        val c = codec ?: return
        if (!running) return
        try {
            var offset = 0
            var remaining = length
            while (remaining > 0 && running) {
                val inIndex = c.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inBuf = c.getInputBuffer(inIndex) ?: return
                    inBuf.clear()
                    val toCopy = minOf(remaining, inBuf.remaining())
                    inBuf.put(data, offset, toCopy)
                    c.queueInputBuffer(inIndex, 0, toCopy, 0, 0)
                    offset += toCopy
                    remaining -= toCopy
                } else {
                    // Decoder busy, wait
                    Thread.sleep(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feed error", e)
        }
    }

    fun stop() {
        running = false
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
    }
}
