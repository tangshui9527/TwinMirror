package com.example.remotescreen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.KeyPair
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Screen mirroring via scrcpy-server (v1.18): the server jar is pushed to
 * /data/local/tmp, started through app_process via the direct ADB protocol
 * (AdbDevice), and streams hardware-encoded H.264 over an adbd `localabstract:`
 * tunnel — no adb forward, no screenrecord quirks. Input goes through the scrcpy
 * control channel (~10ms injection) instead of `input` commands.
 */
class ScrcpyController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onError(msg: String)
        fun onVideoSize(width: Int, height: Int)
        fun onConnected()
        fun onDisconnected()
    }

    private var keyPair: KeyPair? = null // generated lazily on the connect thread (RSA keygen is slow)
    private var device: AdbDevice? = null
    private var videoStream: AdbDevice.ShellStream? = null
    private var controlStream: AdbDevice.ShellStream? = null
    private var control: ControlChannel? = null
    private var serverStream: AdbDevice.ShellStream? = null
    private var videoThread: Thread? = null

    @Volatile private var running = false
    @Volatile private var cancelled = false
    private val disconnected = AtomicBoolean(false)

    // Current video frame size (touch injection coordinate frame; updated on rotation via SPS)
    private var videoWidth = 0
    private var videoHeight = 0

    private var inputExecutor = newInputExecutor()

    private fun newInputExecutor() = Executors.newSingleThreadExecutor { r ->
        thread(name = "control-input", isDaemon = true) { r.run() }
    }

    companion object {
        private const val TAG = "RemoteScreen"
        private const val SCRCPY_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SCRCPY_VERSION = "1.18"
        private const val SOCKET_NAME = "localabstract:scrcpy"
    }

    fun connect(host: String, port: Int) {
        thread(name = "connect") {
            try {
                listener.onLog("连接目标设备: $host:$port ...")
                val pair = keyPair ?: AdbDevice.loadOrCreateKey(context).also { keyPair = it }
                val dev = connectWithAuthRetries(host, port, pair)
                if (cancelled) { dev.close(); return@thread }
                device = dev

                dev.exec("input keyevent KEYCODE_WAKEUP", 8_000)
                runCatching { dev.exec("wm dismiss-keyguard", 8_000) }
                runCatching { dev.exec("wm size", 8_000) }.getOrNull()
                    ?.let { Regex("Physical size: (\\d+)x(\\d+)").find(it) }
                    ?.let { Log.i(TAG, "physical ${it.groupValues[1]}x${it.groupValues[2]}") }

                listener.onLog("推送 scrcpy-server ...")
                pushScrcpyServer(dev)
                dev.exec("pkill -f com.genymobile.scrcpy || true", 4_000)

                // The server runs in the foreground of this stream; its lifetime is the
                // connection's lifetime, so disconnecting cleans it up automatically.
                serverStream = dev.openStream(
                    "CLASSPATH=$SCRCPY_PATH app_process / com.genymobile.scrcpy.Server " +
                        "$SCRCPY_VERSION info 1080 8000000 60 -1 true - false true 0 false true - - false"
                )
                thread(name = "server-log", isDaemon = true) {
                    try {
                        val buf = ByteArray(4096)
                        val inp = serverStream!!.input
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            val line = String(buf, 0, n).trim()
                            if (line.isNotEmpty()) Log.i(TAG, "server: $line")
                        }
                    } catch (_: Exception) {}
                }

                listener.onLog("连接视频流 ...")
                val video = openVideoSocket()
                videoStream = video
                controlStream = openControlSocket()
                control = ControlChannel(controlStream!!.output())

                val vIn = DataInputStream(BufferedInputStream(video.input, 65536))
                val ack = vIn.readUnsignedByte()
                if (ack != 0) throw IOException("视频流 ACK 异常: $ack")
                val nameBytes = ByteArray(64)
                vIn.readFully(nameBytes)
                val deviceName = String(nameBytes).trim { it <= ' ' || it == '\u0000' }
                val w = ((vIn.readUnsignedByte() shl 8) or vIn.readUnsignedByte())
                val h = ((vIn.readUnsignedByte() shl 8) or vIn.readUnsignedByte())
                videoWidth = w
                videoHeight = h
                Log.i(TAG, "device: $deviceName, video ${w}x$h")
                listener.onVideoSize(w, h)

                running = true
                listener.onConnected()
                listener.onLog("已连接 ($deviceName, ${w}x$h)")

                videoThread = thread(name = "video-decode", isDaemon = true) {
                    decodeLoop(vIn)
                }
            } catch (e: AdbDevice.UnauthorizedException) {
                Log.e(TAG, "Connect failed: unauthorized", e)
                listener.onError("连接失败: ${e.message}")
                disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                listener.onError("连接失败: ${e.message}")
                disconnect()
            }
        }
    }

    /**
     * adbd drops sockets stuck in the auth handshake after ~20s; real adb clients
     * transparently reconnect, so the authorization dialog just reappears until
     * answered. Do the same instead of failing the whole connect.
     */
    private fun connectWithAuthRetries(host: String, port: Int, pair: KeyPair): AdbDevice {
        var authRetries = 0
        while (true) {
            val d = AdbDevice(host, port)
            try {
                d.connect(pair) { listener.onLog("请在目标手机上允许 ADB 调试 ...") }
                return d
            } catch (e: AdbDevice.UnauthorizedException) {
                d.close()
                throw e // the full 120s was spent waiting; give up
            } catch (e: Exception) {
                d.close()
                authRetries++
                if (authRetries >= 4 || cancelled) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun openVideoSocket(): AdbDevice.ShellStream {
        var last: Exception? = null
        repeat(20) {
            if (cancelled) throw IOException("cancelled")
            try {
                return device!!.openRawStream(SOCKET_NAME, 3_000)
            } catch (e: Exception) { last = e }
            Thread.sleep(400)
        }
        throw RuntimeException("无法连接视频通道: ${last?.message}")
    }

    private fun openControlSocket(): AdbDevice.ShellStream {
        var last: Exception? = null
        repeat(10) {
            try {
                return device!!.openRawStream(SOCKET_NAME, 3_000)
            } catch (e: Exception) { last = e }
            Thread.sleep(300)
        }
        throw RuntimeException("无法连接控制通道: ${last?.message}")
    }

    private fun pushScrcpyServer(dev: AdbDevice) {
        val jar = context.assets.open("scrcpy_server").use { it.readBytes() }
        val existing = runCatching {
            dev.exec("stat -c %s $SCRCPY_PATH 2>/dev/null || echo missing", 5_000).trim()
        }.getOrNull()
        if (existing == jar.size.toString()) {
            Log.i(TAG, "scrcpy-server already pushed (${jar.size} bytes)")
            return
        }
        Log.i(TAG, "pushing scrcpy-server (${jar.size} bytes), remote=$existing")
        val push = dev.openStream("cat > $SCRCPY_PATH", 5_000)
        push.writeStdin(jar)
        push.closeStdin()
        // wait for cat to finish (stream EOFs when the shell exits)
        val deadline = System.currentTimeMillis() + 10_000
        try {
            val buf = ByteArray(4096)
            val inp = push.input
            while (System.currentTimeMillis() < deadline) {
                if (inp.read(buf) < 0) break
            }
        } catch (_: Exception) {}
        push.close()
        dev.exec("chmod 644 $SCRCPY_PATH", 4_000)
        val check = dev.exec("stat -c %s $SCRCPY_PATH", 4_000).trim()
        if (check != jar.size.toString()) throw IOException("推送校验失败: $check != ${jar.size}")
    }

    private fun decodeLoop(input: DataInputStream) {
        var codec: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        try {
            // The SurfaceView surface exists only after the video layout becomes visible
            var currentSurface = VideoDecoderHolder.surface
            var waited = 0
            while (currentSurface == null && waited < 5000) {
                Thread.sleep(100)
                waited += 100
                currentSurface = VideoDecoderHolder.surface
            }
            if (currentSurface == null) throw IllegalStateException("Surface not ready")

            val readBuf = ByteArray(65536)
            val nalBuf = java.io.ByteArrayOutputStream(256 * 1024)

            while (running) {
                // The SurfaceView is destroyed and recreated on app pause/resume; the
                // decoder must follow or it keeps rendering to a dead surface.
                val liveSurface = VideoDecoderHolder.surface
                if (liveSurface != null && liveSurface !== currentSurface) {
                    currentSurface = liveSurface
                    try { codec?.stop() } catch (_: Exception) {}
                    try { codec?.release() } catch (_: Exception) {}
                    codec = null
                    val s = sps
                    val p = pps
                    if (s != null && p != null) {
                        codec = initDecoder(s, p, videoWidth, videoHeight, liveSurface)
                    }
                }

                val n = input.read(readBuf, 0, readBuf.size)
                if (n <= 0) break
                nalBuf.write(readBuf, 0, n)

                val data = nalBuf.toByteArray()
                var consumed = 0

                while (true) {
                    val cur = findStartCode(data, consumed) ?: break
                    val next = findStartCode(data, cur.second) ?: break

                    val nal = data.copyOfRange(cur.first, next.first)
                    val payloadOff = cur.second - cur.first
                    val nalType = if (nal.size > payloadOff) nal[payloadOff].toInt() and 0x1F else -1

                    when (nalType) {
                        7 -> {
                            // Early size hint; the decoder's output format (feedNal) is
                            // authoritative and overrides this.
                            val dims = SpsParser.parseResolution(nal)
                            if (dims != null && (dims.first != videoWidth || dims.second != videoHeight)) {
                                videoWidth = dims.first
                                videoHeight = dims.second
                                listener.onVideoSize(dims.first, dims.second)
                                Log.i(TAG, "video size (sps): ${dims.first}x${dims.second}")
                            }
                            sps = nal
                        }
                        8 -> pps = nal
                        5 -> {
                            if (codec == null && sps != null && pps != null) {
                                codec = initDecoder(sps, pps, videoWidth, videoHeight, currentSurface!!)
                            }
                        }
                    }

                    if (codec != null) {
                        feedNal(codec, nal)
                    }

                    consumed = next.first
                }

                if (consumed > 0) {
                    val remaining = data.copyOfRange(consumed, data.size)
                    nalBuf.reset()
                    nalBuf.write(remaining)
                }
                if (nalBuf.size() > 4 * 1024 * 1024) nalBuf.reset()
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Decode error", e)
                listener.onError("视频错误: ${e.message}")
            }
        } finally {
            val wasRunning = running
            running = false
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            if (wasRunning) notifyDisconnected()
        }
    }

    private fun initDecoder(sps: ByteArray, pps: ByteArray, w: Int, h: Int, surface: android.view.Surface): MediaCodec? {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            Log.i(TAG, "decoder started ${w}x$h")
            c
        } catch (e: Exception) {
            Log.e(TAG, "init decoder failed", e)
            null
        }
    }

    private fun feedNal(codec: MediaCodec, nal: ByteArray) {
        try {
            var offset = 0
            while (offset < nal.size && running) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) continue
                val buf = codec.getInputBuffer(idx) ?: return
                buf.clear()
                val n = minOf(nal.size - offset, buf.remaining())
                buf.put(nal, offset, n)
                codec.queueInputBuffer(idx, 0, n, 0, 0)
                offset += n
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val outIdx = codec.dequeueOutputBuffer(info, 0)
                if (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true)
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Authoritative frame size: fires on first output and on rotation,
                    // and is correct where a hand-rolled SPS parse may drift.
                    val f = codec.outputFormat
                    runCatching {
                        val w = f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                            videoWidth = w
                            videoHeight = h
                            listener.onVideoSize(w, h)
                            Log.i(TAG, "video size (decoder): ${w}x$h")
                        }
                    }
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "feed error", e)
        }
    }

    /** Finds the next Annex-B start code (00 00 01 or 00 00 00 01) at or after `from`.
     *  Returns (codeStart, payloadStart); extra leading zeros of a 4-byte code are
     *  absorbed into codeStart. Encoders commonly emit 3-byte codes for non-IDR frames. */
    private fun findStartCode(data: ByteArray, from: Int): Pair<Int, Int>? {
        var i = from
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                var s = i
                while (s > from && data[s - 1] == 0.toByte()) s--
                return Pair(s, i + 3)
            }
            i++
        }
        return null
    }

    // ===== Input via control channel =====

    private fun submit(block: () -> Unit) {
        synchronized(this) {
            if (inputExecutor.isShutdown) inputExecutor = newInputExecutor()
        }
        try {
            inputExecutor.execute {
                try { block() } catch (e: Exception) { Log.e(TAG, "input inject failed", e) }
            }
        } catch (_: RejectedExecutionException) {}
    }

    /** Tap (down+up) or long press at video-frame coordinates. */
    fun sendTouch(x: Int, y: Int, width: Int, height: Int, isLongPress: Boolean = false) {
        if (!running) return
        val c = control ?: return
        submit {
            if (isLongPress) {
                c.touch(ControlChannel.ACTION_DOWN, x, y, width, height, ControlChannel.NORMAL_PRESSURE)
                Thread.sleep(700)
                c.touch(ControlChannel.ACTION_UP, x, y, width, height, ControlChannel.NO_PRESSURE)
            } else {
                c.touch(ControlChannel.ACTION_DOWN, x, y, width, height, ControlChannel.NORMAL_PRESSURE)
                c.touch(ControlChannel.ACTION_UP, x, y, width, height, ControlChannel.NO_PRESSURE)
            }
        }
    }

    fun sendSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, width: Int, height: Int, durationMs: Int = 300) {
        if (!running) return
        val c = control ?: return
        submit {
            c.touch(ControlChannel.ACTION_DOWN, x1, y1, width, height, ControlChannel.NORMAL_PRESSURE)
            val steps = 8
            val dur = durationMs.coerceIn(100, 500)
            for (i in 1 until steps) {
                Thread.sleep(dur.toLong() / steps)
                val ix = x1 + (x2 - x1) * i / steps
                val iy = y1 + (y2 - y1) * i / steps
                c.touch(ControlChannel.ACTION_MOVE, ix, iy, width, height, ControlChannel.NORMAL_PRESSURE)
            }
            Thread.sleep(dur.toLong() / steps)
            c.touch(ControlChannel.ACTION_UP, x2, y2, width, height, ControlChannel.NO_PRESSURE)
        }
    }

    fun sendKey(keycode: Int) {
        if (!running) return
        val c = control ?: return
        submit { c.tapKey(keycode) }
    }

    fun wakeUp() {
        if (!running) return
        val c = control ?: return
        submit {
            c.tapKey(224) // KEYCODE_WAKEUP
        }
    }

    fun disconnect() {
        cancelled = true
        running = false
        val dev = device
        try { videoStream?.close() } catch (_: Exception) {}
        try { controlStream?.close() } catch (_: Exception) {}
        try { serverStream?.close() } catch (_: Exception) {}
        try { videoThread?.interrupt() } catch (_: Exception) {}
        try { inputExecutor.shutdownNow() } catch (_: Exception) {}
        try { dev?.close() } catch (_: Exception) {}
        device = null
        control = null
        notifyDisconnected()
    }

    private fun notifyDisconnected() {
        if (disconnected.compareAndSet(false, true)) {
            running = false
            listener.onDisconnected()
        }
    }

    fun isRunning(): Boolean = running
}
