package com.example.remotescreen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * Screen mirroring via scrcpy server (H.264 stream) + real-time control channel input.
 */
class ScrcpyController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onError(msg: String)
        fun onVideoSize(width: Int, height: Int)
        fun onConnected()
        fun onDisconnected()
    }

    private var adb: AdbClient? = null
    private var videoThread: Thread? = null
    private var videoSocket: Socket? = null
    private var controlSocket: Socket? = null
    private var control: ControlChannel? = null
    @Volatile private var running = false

    // All control-channel writes must leave the UI thread (NetworkOnMainThreadException otherwise)
    private var inputExecutor = newInputExecutor()

    private fun newInputExecutor() = Executors.newSingleThreadExecutor { r ->
        thread(name = "control-input", isDaemon = true) { r.run() }
    }

    // Physical screen size (for stats/log and injection frame if needed)
    private var devicePhysicalWidth = 0
    private var devicePhysicalHeight = 0

    // Current video frame size (touch injection coordinate frame; updated on rotation via SPS)
    private var videoWidth = 0
    private var videoHeight = 0

    companion object {
        private const val TAG = "RemoteScreen"
        private const val SOCKET_PORT = 5005
        private const val SCRCPY_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SCRCPY_VERSION = "1.18"
    }

    fun connect(host: String, port: Int) {
        thread(name = "connect") {
            try {
                inputExecutor.shutdownNow()
                inputExecutor = newInputExecutor()
                Log.i(TAG, "connect start")
                listener.onLog("连接 ADB: $host:$port ...")
                val client = AdbClient(context, host, port)
                client.connect()
                adb = client
                Log.i(TAG, "adb connected")

                // Wake up device
                client.execShell("input keyevent KEYCODE_WAKEUP")
                client.execShell("wm dismiss-keyguard")

                // Get physical resolution
                val wmSize = client.execShell("wm size")
                val resMatch = Regex("Physical size: (\\d+)x(\\d+)").find(wmSize)
                if (resMatch != null) {
                    devicePhysicalWidth = resMatch.groupValues[1].toInt()
                    devicePhysicalHeight = resMatch.groupValues[2].toInt()
                } else {
                    devicePhysicalWidth = 1080
                    devicePhysicalHeight = 2340
                }
                Log.i(TAG, "physical ${devicePhysicalWidth}x${devicePhysicalHeight}")

                // Push scrcpy-server (skip if identical file already there)
                listener.onLog("推送 scrcpy-server ...")
                pushScrcpyServer(client)

                // Stale server killed; new forward set (HOST command, not device shell!)
                client.execShell("pkill -f com.genymobile.scrcpy || true")
                Thread.sleep(100)
                client.forward("tcp:$SOCKET_PORT", "localabstract:scrcpy")
                Log.i(TAG, "forward set")

                // Start server (control=true so both sockets work; input injected via control channel)
                startScrcpyServer(client)
                Thread.sleep(200)

                // Video socket: connect + 1-byte ACK
                listener.onLog("连接视频流 ...")
                val vIn = connectWithAck()

                // Second connection: control socket (v1.18 always accepts two)
                val cs = connectControl()
                controlSocket = cs
                control = ControlChannel(cs.getOutputStream())

                // Header on video socket: 64-byte name + 2-byte width + 2-byte height (BE)
                val nameBytes = ByteArray(64)
                vIn.readFully(nameBytes)
                val deviceName = String(nameBytes).trim { it <= ' ' || it == '\u0000' }
                Log.i(TAG, "device: $deviceName")
                val w = ((vIn.readUnsignedByte() shl 8) or vIn.readUnsignedByte())
                val h = ((vIn.readUnsignedByte() shl 8) or vIn.readUnsignedByte())
                videoWidth = w
                videoHeight = h
                Log.i(TAG, "video $w x $h")
                listener.onVideoSize(w, h)

                running = true
                listener.onConnected()
                listener.onLog("已连接 (视频 ${w}x${h})")

                // Forward no longer needed once sockets are established
                try { client.removeForward("tcp:$SOCKET_PORT") } catch (_: Exception) {}

                // Decode thread
                videoThread = thread(name = "video-decode", isDaemon = true) {
                    decodeLoop(vIn)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                listener.onError("连接失败: ${e.message}")
                disconnect(false)
            }
        }
    }

    private fun connectWithAck(): DataInputStream {
        var lastError: Exception? = null
        for (attempt in 1..10) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", SOCKET_PORT), 2000)
                s.tcpNoDelay = true
                val ack = s.getInputStream().read()
                if (ack == 0) {
                    Log.i(TAG, "video socket ok (attempt $attempt)")
                    return DataInputStream(BufferedInputStream(s.getInputStream(), 65536))
                }
                Log.i(TAG, "ack=$ack, retry")
                s.close()
                lastError = IOException("bad ack $ack")
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(200)
        }
        throw RuntimeException("无法连接视频流: ${lastError?.message}")
    }

    private fun connectControl(): Socket {
        var lastError: Exception? = null
        for (attempt in 1..5) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", SOCKET_PORT), 2000)
                s.tcpNoDelay = true
                Log.i(TAG, "control socket ok")
                return s
            } catch (e: Exception) {
                lastError = e
            }
            Thread.sleep(200)
        }
        throw RuntimeException("无法连接控制通道: ${lastError?.message}")
    }

    private fun pushScrcpyServer(client: AdbClient) {
        val assets = context.assets
        val len = assets.open("scrcpy_server").use { it.available() }
        // Skip re-push if identical file already exists on device
        val existing = client.execShell("stat -c %s $SCRCPY_PATH 2>/dev/null || echo missing")
        if (existing.trim() == len.toString()) {
            Log.i(TAG, "scrcpy-server already pushed ($len bytes)")
            return
        }
        Log.i(TAG, "pushing scrcpy-server ($len bytes)")
        val tmp = File.createTempFile("scrcpy", ".jar", context.cacheDir)
        assets.open("scrcpy_server").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        try {
            client.execPush(tmp.absolutePath, SCRCPY_PATH)
            client.execShell("chmod 755 $SCRCPY_PATH")
        } finally {
            tmp.delete()
        }
    }

    private fun startScrcpyServer(client: AdbClient) {
        // scrcpy 1.18 positional args:
        // 0:version 1:logLevel 2:maxSize 3:bitRate 4:maxFps 5:orientation
        // 6:tunnelForward 7:crop 8:sendFrameMeta 9:control 10:displayId
        // 11:showTouches 12:stayAwake 13:codecOptions 14:encoderName 15:powerOffScreenOnClose
        val maxSize = 1080 // video stream downscale; target device itself untouched
        val bitRate = 8_000_000
        val maxFps = 60
        val cmd = "CLASSPATH=$SCRCPY_PATH nohup app_process / com.genymobile.scrcpy.Server " +
            "$SCRCPY_VERSION info $maxSize $bitRate $maxFps -1 true - false true 0 false true - - false " +
            ">/data/local/tmp/scrcpy.log 2>&1 < /dev/null &"
        thread(name = "scrcpy-server", isDaemon = true) {
            try {
                client.execShell(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "scrcpy launch error", e)
            }
        }
    }

    private fun decodeLoop(input: DataInputStream) {
        var codec: MediaCodec? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var lastSpsWidth = 0
        try {
            val surface = VideoDecoderHolder.surface
            ?: throw IllegalStateException("Surface not ready")

            val readBuf = ByteArray(65536)
            val nalBuf = java.io.ByteArrayOutputStream(256 * 1024)

            while (running) {
                // Blocking read; returns -1 on EOF
                val n = input.read(readBuf, 0, readBuf.size)
                if (n <= 0) break
                nalBuf.write(readBuf, 0, n)

                val data = nalBuf.toByteArray()
                var consumed = 0

                while (true) {
                    val start = findStartCode(data, consumed)
                    if (start < 0) break
                    val next = findStartCode(data, start + 4)
                    if (next < 0) break

                    val nal = data.copyOfRange(start, next)
                    val nalType = if (nal.size > 4) nal[4].toInt() and 0x1F else -1

                    when (nalType) {
                        7 -> {
                            // Track rotation: SPS size changes => notify UI for touch mapping
                            val dims = SpsParser.parseResolution(nal)
                            if (dims != null && dims.first != lastSpsWidth) {
                                lastSpsWidth = dims.first
                                videoWidth = dims.first
                                videoHeight = dims.second
                                listener.onVideoSize(dims.first, dims.second)
                                Log.i(TAG, "video size changed: ${dims.first}x${dims.second}")
                            }
                            sps = nal
                        }
                        8 -> pps = nal
                        5 -> {
                            if (codec == null && sps != null && pps != null) {
                                codec = initDecoder(sps, pps, videoWidth, videoHeight, surface)
                            }
                        }
                    }

                    if (codec != null) {
                        feedNal(codec, nal)
                    }

                    consumed = next
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
            if (wasRunning) listener.onDisconnected()
        }
    }

    private fun initDecoder(sps: ByteArray, pps: ByteArray, w: Int, h: Int, surface: Surface): MediaCodec? {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, surface, null, 0)
            c.start()
            Log.i(TAG, "decoder started ${w}x${h}")
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
            // Drain rendered frames
            val info = MediaCodec.BufferInfo()
            var outIdx = codec.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                codec.releaseOutputBuffer(outIdx, true)
                outIdx = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "feed error", e)
        }
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        for (i in from until data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                return i
            }
        }
        return -1
    }

    // ===== Input via control channel (low latency ~10ms) =====
    // Writes must run off the UI thread; single-thread executor keeps DOWN/MOVE/UP order.

    private fun submit(block: () -> Unit) {
        synchronized(this) {
            if (inputExecutor.isShutdown) inputExecutor = newInputExecutor()
        }
        try {
            inputExecutor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "input inject failed", e)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** action: 0=down 2=move 1=up; coordinates in current video frame */
    fun sendTouch(action: Int, x: Int, y: Int) {
        val c = control ?: return
        if (!running) return
        submit {
            val pressure = if (action == ControlChannel.ACTION_UP) ControlChannel.NO_PRESSURE else ControlChannel.NORMAL_PRESSURE
            c.touch(action, x, y, videoWidth, videoHeight, pressure)
        }
    }

    fun sendSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        if (!running) return
        submit {
            try {
                val c = control ?: return@submit
                c.touch(ControlChannel.ACTION_DOWN, x1, y1, videoWidth, videoHeight, ControlChannel.NORMAL_PRESSURE)
                // Interpolate for smooth gesture
                val steps = 12
                for (i in 1 until steps) {
                    val x = x1 + (x2 - x1) * i / steps
                    val y = y1 + (y2 - y1) * i / steps
                    c.touch(ControlChannel.ACTION_MOVE, x, y, videoWidth, videoHeight, ControlChannel.NORMAL_PRESSURE)
                    Thread.sleep(durationMs.toLong() / steps)
                }
                c.touch(ControlChannel.ACTION_UP, x2, y2, videoWidth, videoHeight, ControlChannel.NO_PRESSURE)
            } catch (e: Exception) {
                Log.e(TAG, "swipe inject failed", e)
            }
        }
    }

    fun sendKey(keycode: Int) {
        if (!running) return
        val c = control ?: return
        submit { c.tapKey(keycode) }
    }

    /** Wake the target (screen-on) via control channel */
    fun wakeUp() {
        if (!running) return
        val c = control ?: return
        submit { c.wakeOrBack() }
    }

    /**
     * @param cleanup true = full teardown (activity destroyed): disconnect adb, stop service.
     *                false = light stop: kill scrcpy only so reconnect is fast.
     */
    fun disconnect(cleanup: Boolean = false) {
        val wasRunning = running
        running = false
        inputExecutor.shutdownNow()
        try { videoSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { videoThread?.interrupt() } catch (_: Exception) {}
        try { adb?.execShell("pkill -f com.genymobile.scrcpy || true") } catch (_: Exception) {}
        if (cleanup) {
            try { adb?.close() } catch (_: Exception) {}
            try { AdbKeepAliveService.stop(context) } catch (_: Exception) {}
        }
        adb = null
        if (wasRunning) listener.onDisconnected()
    }

    fun isRunning(): Boolean = running
}
