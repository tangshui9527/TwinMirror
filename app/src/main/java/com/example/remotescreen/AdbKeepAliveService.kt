package com.example.remotescreen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service that keeps the adb server alive.
 * Prevents Android from killing the adb server process.
 */
class AdbKeepAliveService : Service() {

    companion object {
        private const val TAG = "RemoteScreen"
        private const val CHANNEL_ID = "adb_keepalive"
        private const val NOTIFICATION_ID = 1
        @Volatile private var serverProcess: Process? = null
        @Volatile private var adbPath: String? = null

        const val ACTION_START = "com.example.remotescreen.START_ADB"
        const val ACTION_STOP = "com.example.remotescreen.STOP_ADB"
        const val EXTRA_PORT = "port"

        fun start(context: Context, port: Int) {
            val intent = Intent(context, AdbKeepAliveService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PORT, port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbKeepAliveService::class.java)
                .setAction(ACTION_STOP)
            context.stopService(intent)
        }

        fun isRunning(): Boolean = serverProcess?.isAlive == true

        private fun ensureAdb(context: Context): String {
            adbPath?.let { return it }
            synchronized(this) {
                adbPath?.let { return it }
                val file = File(context.filesDir, "adb")
                if (!file.exists() || file.length() == 0L) {
                    context.assets.open("adb").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                file.setExecutable(true, false)
                adbPath = file.absolutePath
                return file.absolutePath
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 5038)
                startForeground(NOTIFICATION_ID, createNotification())
                startAdbServer(port)
            }
            ACTION_STOP -> {
                stopAdbServer()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADB 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 ADB 连接活跃"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("远程投屏")
                .setContentText("ADB 服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("远程投屏")
                .setContentText("ADB 服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .build()
        }
    }

    private fun startAdbServer(port: Int) {
        synchronized(this) {
            val existing = serverProcess
            if (existing != null && existing.isAlive) {
                Log.i(TAG, "adb server already running")
                return
            }
            Log.i(TAG, "Starting adb server on port $port")
            val adb = ensureAdb(this)
            val pb = ProcessBuilder(adb, "-P", port.toString(), "nodaemon", "server")
                .redirectErrorStream(true)
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["ANDROID_DATA"] = filesDir.absolutePath
            val p = pb.start()
            serverProcess = p
            Thread {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.d(TAG, "adb-server: $line")
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            Thread.sleep(1000)
            Log.i(TAG, "adb server started (alive=${p.isAlive})")
        }
    }

    private fun stopAdbServer() {
        synchronized(this) {
            serverProcess?.destroy()
            serverProcess = null
            Log.i(TAG, "adb server stopped")
        }
    }

    override fun onDestroy() {
        stopAdbServer()
        super.onDestroy()
    }
}
