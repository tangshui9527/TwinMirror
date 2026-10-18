package com.example.remotescreen

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Screen mirroring using screencap polling + input commands for control.
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
    private var captureThread: Thread? = null
    private var keepAliveThread: Thread? = null
    @Volatile private var running = false
    private var deviceWidth = 0
    private var deviceHeight = 0

    // Touch command queue to serialize ADB input commands
    private val cmdQueue = LinkedBlockingQueue<String>()
    private var cmdThread: Thread? = null

    fun connect(host: String, port: Int) {
        thread(name = "connect") {
            try {
                listener.onLog("连接 ADB: $host:$port ...")
                val client = AdbClient(context, host, port)
                client.connect()
                adb = client
                listener.onLog("ADB 已连接")

                // Wake up and keep screen on
                listener.onLog("唤醒目标设备 ...")
                client.execShell("input keyevent KEYCODE_WAKEUP")
                client.execShell("wm dismiss-keyguard")
                client.execShell("settings put system screen_off_timeout 1800000")
                try { client.execShell("svc power stayon true") } catch (_: Exception) {}
                Thread.sleep(200)

                // Get screen resolution
                val wmSize = client.execShell("wm size")
                val resMatch = Regex("Physical size: (\\d+)x(\\d+)").find(wmSize)
                if (resMatch != null) {
                    deviceWidth = resMatch.groupValues[1].toInt()
                    deviceHeight = resMatch.groupValues[2].toInt()
                } else {
                    deviceWidth = 1080
                    deviceHeight = 2340
                }
                listener.onLog("屏幕: ${deviceWidth}x$deviceHeight")
                listener.onVideoSize(deviceWidth, deviceHeight)

                running = true
                listener.onConnected()

                // Start command worker
                cmdThread = thread(name = "cmd-worker", isDaemon = true) {
                    while (running) {
                        try {
                            val cmd = cmdQueue.poll(100, TimeUnit.MILLISECONDS)
                            if (cmd != null) {
                                adb?.execShell(cmd)
                            }
                        } catch (e: InterruptedException) { break }
                          catch (e: Exception) { Log.e(TAG, "Cmd failed", e) }
                    }
                }

                // Start keep-alive
                keepAliveThread = thread(name = "keep-alive", isDaemon = true) {
                    while (running) {
                        Thread.sleep(30000)
                        if (running) adb?.execShell("input keyevent KEYCODE_WAKEUP")
                    }
                }

                // Start screen capture polling
                captureThread = thread(name = "screen-capture", isDaemon = true) {
                    captureLoop()
                }

            } catch (e: Exception) {
                listener.onError("连接失败: ${e.message}")
                Log.e(TAG, "Connect failed", e)
                disconnect()
            }
        }
    }

    private fun captureLoop() {
        var failCount = 0
        while (running) {
            try {
                val client = adb ?: break
                val proc = client.openExecOut("screencap -p")
                val bytes = proc.inputStream.readBytes()
                // Wait with timeout to avoid hanging
                if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    throw RuntimeException("screencap timeout")
                }

                if (bytes.isNotEmpty() && running) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        drawToSurface(bitmap)
                        bitmap.recycle()
                        failCount = 0
                    }
                }

                Thread.sleep(200) // ~5 FPS
            } catch (e: Exception) {
                failCount++
                Log.e(TAG, "Capture failed ($failCount)", e)
                if (failCount > 10) {
                    if (running) listener.onError("截图失败多次: ${e.message}")
                    break
                }
                Thread.sleep(1000)
            }
        }
        running = false
        listener.onDisconnected()
    }

    private fun drawToSurface(bitmap: android.graphics.Bitmap) {
        val holder = VideoDecoderHolder.holder ?: return
        try {
            val canvas = holder.lockCanvas() ?: return
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val cw = canvas.width.toFloat()
                val ch = canvas.height.toFloat()
                val scale = minOf(cw / bw, ch / bh)
                val dx = (cw - bw * scale) / 2
                val dy = (ch - bh * scale) / 2
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(dx, dy, dx + bw * scale, dy + bh * scale), null)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Draw failed", e)
        }
    }

    /** Send tap or long press at device coordinates */
    fun sendTouch(action: Int, x: Int, y: Int, width: Int, height: Int, isLongPress: Boolean = false) {
        if (!running || deviceWidth == 0) return
        val dx = (x * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy = (y * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        if (action == 1) {
            val cmd = if (isLongPress) "input swipe $dx $dy $dx $dy 800" else "input tap $dx $dy"
            cmdQueue.offer(cmd)
        }
    }

    /** Send swipe gesture from (x1,y1) to (x2,y2) in view coordinates */
    fun sendSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, width: Int, height: Int, durationMs: Int = 300) {
        if (!running || deviceWidth == 0) return
        val dx1 = (x1 * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy1 = (y1 * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        val dx2 = (x2 * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy2 = (y2 * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        cmdQueue.offer("input swipe $dx1 $dy1 $dx2 $dy2 $durationMs")
    }

    fun sendKey(keycode: Int) {
        if (!running) return
        cmdQueue.offer("input keyevent $keycode")
    }

    fun wakeUp() {
        if (!running) return
        cmdQueue.offer("input keyevent KEYCODE_WAKEUP")
        cmdQueue.offer("wm dismiss-keyguard")
    }

    fun disconnect() {
        running = false
        cmdQueue.clear()
        try { keepAliveThread?.interrupt() } catch (_: Exception) {}
        try { captureThread?.interrupt() } catch (_: Exception) {}
        try { cmdThread?.interrupt() } catch (_: Exception) {}
        try { adb?.close() } catch (_: Exception) {}
        adb = null
        listener.onDisconnected()
    }

    fun isRunning(): Boolean = running

    companion object {
        private const val TAG = "RemoteScreen"
    }
}
