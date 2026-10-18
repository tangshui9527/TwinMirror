package com.example.remotescreen

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.io.BufferedInputStream

/**
 * Screen mirroring using screencap polling + input commands for control.
 * More stable than video streaming, no garbled frames.
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
    private var lastBitmap: android.graphics.Bitmap? = null

    fun connect(host: String, port: Int) {
        Thread {
            try {
                listener.onLog("连接 ADB: $host:$port ...")
                val client = AdbClient(context, host, port)
                client.connect()
                adb = client
                listener.onLog("ADB 已连接")

                // Wake up target device
                listener.onLog("唤醒目标设备 ...")
                client.execShell("input keyevent KEYCODE_WAKEUP")
                client.execShell("wm dismiss-keyguard")
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

                // Start keep-alive
                keepAliveThread = Thread {
                    while (running) {
                        try {
                            Thread.sleep(15000)
                            if (running) adb?.execShell("echo ka")
                        } catch (e: Exception) { break }
                    }
                }.apply { isDaemon = true; start() }

                // Start screen capture polling
                captureThread = Thread { captureLoop() }.apply {
                    name = "screen-capture"
                    isDaemon = true
                    start()
                }

            } catch (e: Exception) {
                listener.onError("连接失败: ${e.message}")
                e.printStackTrace()
                disconnect()
            }
        }.start()
    }

    private fun captureLoop() {
        var failCount = 0
        while (running) {
            try {
                val client = adb ?: break
                // Capture screen as PNG
                val proc = client.openExecOut("screencap -p")
                val bytes = proc.inputStream.readBytes()
                proc.waitFor()

                if (bytes.isNotEmpty() && running) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        lastBitmap?.recycle()
                        lastBitmap = bitmap
                        failCount = 0
                        // Draw to surface
                        drawToSurface(bitmap)
                    }
                }

                // ~5 FPS for stability
                Thread.sleep(200)
            } catch (e: Exception) {
                failCount++
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
        val canvas = holder.lockCanvas() ?: return
        try {
            // Clear and draw with aspect ratio
            canvas.drawColor(android.graphics.Color.BLACK)
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val cw = canvas.width.toFloat()
            val ch = canvas.height.toFloat()
            val scale = minOf(cw / bw, ch / bh)
            val dx = (cw - bw * scale) / 2
            val dy = (ch - bh * scale) / 2
            val dstRect = android.graphics.RectF(dx, dy, dx + bw * scale, dy + bh * scale)
            canvas.drawBitmap(bitmap, null, dstRect, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    /** Send tap, long press, or swipe at device coordinates */
    fun sendTouch(action: Int, x: Int, y: Int, width: Int, height: Int, isLongPress: Boolean = false) {
        val client = adb ?: return
        val dx = (x * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy = (y * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        Thread {
            try {
                if (action == 1) { // UP
                    if (isLongPress) {
                        client.execShell("input swipe $dx $dy $dx $dy 800")
                    } else {
                        client.execShell("input tap $dx $dy")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Touch failed", e)
            }
        }.start()
    }

    /** Send swipe gesture from (x1,y1) to (x2,y2) */
    fun sendSwipeGesture(x1: Int, y1: Int, x2: Int, y2: Int, width: Int, height: Int, durationMs: Int = 300) {
        val client = adb ?: return
        val dx1 = (x1 * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy1 = (y1 * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        val dx2 = (x2 * deviceWidth / width).coerceIn(0, deviceWidth - 1)
        val dy2 = (y2 * deviceHeight / height).coerceIn(0, deviceHeight - 1)
        Thread {
            try {
                client.execShell("input swipe $dx1 $dy1 $dx2 $dy2 $durationMs")
            } catch (e: Exception) {
                Log.e(TAG, "Swipe failed", e)
            }
        }.start()
    }

    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        val client = adb ?: return
        Thread {
            try {
                client.execShell("input swipe $x1 $y1 $x2 $y2 $durationMs")
            } catch (e: Exception) { Log.e(TAG, "Swipe failed", e) }
        }.start()
    }

    fun sendBack() {
        Thread { adb?.execShell("input keyevent 4") }.start()
    }

    fun sendKey(keycode: Int) {
        Thread { adb?.execShell("input keyevent $keycode") }.start()
    }

    fun disconnect() {
        running = false
        try { keepAliveThread?.interrupt() } catch (_: Exception) {}
        try { captureThread?.interrupt() } catch (_: Exception) {}
        try { adb?.execShell("pkill -f screencap") } catch (_: Exception) {}
        try { adb?.close() } catch (_: Exception) {}
        try { AdbKeepAliveService.stop(context) } catch (_: Exception) {}
        lastBitmap?.recycle()
        lastBitmap = null
        adb = null
        listener.onDisconnected()
    }

    fun isRunning(): Boolean = running

    companion object {
        private const val TAG = "RemoteScreen"
    }
}
