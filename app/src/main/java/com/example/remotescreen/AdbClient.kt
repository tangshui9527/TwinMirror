package com.example.remotescreen

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * ADB client using bundled adb binary with persistent server managed by foreground service.
 */
class AdbClient(private val context: Context, private val host: String, private val port: Int) {

    companion object {
        private const val TAG = "RemoteScreen"
        const val ADB_PORT = 5038
        @Volatile private var adbPath: String? = null

        fun ensureAdb(context: Context): String {
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
                Log.i(TAG, "adb extracted to ${file.absolutePath}")
                return file.absolutePath
            }
        }
    }

    private val serial = "$host:$port"

    private fun execAdb(args: List<String>): String {
        val adb = ensureAdb(context)
        val cmd = listOf(adb, "-P", ADB_PORT.toString()) + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["HOME"] = context.filesDir.absolutePath
        pb.environment()["ANDROID_DATA"] = context.filesDir.absolutePath
        val process = pb.start()
        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RuntimeException("adb command timeout")
        }
        return output.toString().trim()
    }

    fun connect() {
        if (!AdbKeepAliveService.isRunning()) {
            AdbKeepAliveService.start(context, ADB_PORT)
            for (i in 0 until 15) {
                Thread.sleep(100)
                if (AdbKeepAliveService.isRunning()) break
            }
        }
        var result = ""
        for (i in 1..3) {
            result = execAdb(listOf("connect", serial))
            Log.i(TAG, "connect attempt $i: $result")
            if (result.contains("connected") || result.contains("already")) break
            Thread.sleep(200)
        }
        if (!result.contains("connected") && !result.contains("already")) {
            throw RuntimeException("无法连接到 $serial")
        }
    }

    fun execShell(cmd: String): String {
        return execAdb(listOf("-s", serial, "shell", cmd))
    }

    fun execPush(localPath: String, remotePath: String) {
        execAdb(listOf("-s", serial, "push", localPath, remotePath))
    }

    fun forward(local: String, remote: String) {
        val result = execAdb(listOf("-s", serial, "forward", local, remote))
        Log.i(TAG, "forward: $result")
    }

    fun removeForward(local: String) {
        execAdb(listOf("-s", serial, "forward", "--remove", local))
    }

    fun close() {
        try { execAdb(listOf("disconnect", serial)) } catch (_: Exception) {}
    }
}
