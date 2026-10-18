package com.example.remotescreen

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream

class MainActivity : Activity(), ScrcpyController.Listener, SurfaceHolder.Callback {

    private lateinit var connectLayout: LinearLayout
    private lateinit var videoLayout: LinearLayout
    private lateinit var etAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnBack: Button
    private lateinit var btnHome: Button
    private lateinit var btnRecent: Button
    private lateinit var tvStatus: TextView
    private lateinit var surfaceView: SurfaceView

    private var controller: ScrcpyController? = null
    private var videoWidth = 0
    private var videoHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectLayout = findViewById(R.id.connectLayout)
        videoLayout = findViewById(R.id.videoLayout)
        etAddress = findViewById(R.id.etAddress)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        btnRecent = findViewById(R.id.btnRecent)
        tvStatus = findViewById(R.id.tvStatus)
        surfaceView = findViewById(R.id.surfaceView)

        surfaceView.holder.addCallback(this)

        // Set default address
        etAddress.setText("192.168.31.17:5555")

        btnConnect.setOnClickListener {
            val addr = etAddress.text.toString().trim()
            if (addr.isEmpty()) {
                Toast.makeText(this, "请输入目标手机 IP:端口", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val (host, port) = parseAddress(addr)
            connectTo(host, port)
        }

        btnDisconnect.setOnClickListener {
            disconnect()
        }

        // Navigation buttons
        btnBack.setOnClickListener {
            controller?.sendKey(4) // KEYCODE_BACK
        }
        btnHome.setOnClickListener {
            controller?.sendKey(3) // KEYCODE_HOME
        }
        btnRecent.setOnClickListener {
            controller?.sendKey(187) // KEYCODE_APP_SWITCH
        }

        // Touch forwarding
        setupTouchForwarding()
    }

    private fun parseAddress(addr: String): Pair<String, Int> {
        val parts = addr.split(":")
        return if (parts.size == 2) {
            parts[0] to (parts[1].toIntOrNull() ?: 5555)
        } else {
            addr to 5555
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchForwarding() {
        var downTime = 0L
        var downX = 0f
        var downY = 0f
        var lastTapTime = 0L
        var lastTapX = 0f
        var lastTapY = 0f

        surfaceView.setOnTouchListener { v, event ->
            val ctrl = controller ?: return@setOnTouchListener false
            if (videoWidth == 0 || videoHeight == 0) return@setOnTouchListener false

            // Map touch coordinates from surface view to device screen
            val sx = v.width.toFloat()
            val sy = v.height.toFloat()
            val scale = minOf(sx / videoWidth, sy / videoHeight)
            val offsetX = (sx - videoWidth * scale) / 2
            val offsetY = (sy - videoHeight * scale) / 2

            val deviceX = ((event.x - offsetX) / scale).toInt().coerceIn(0, videoWidth - 1)
            val deviceY = ((event.y - offsetY) / scale).toInt().coerceIn(0, videoHeight - 1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - downTime
                    val dx = event.x - downX
                    val dy = event.y - downY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    val now = System.currentTimeMillis()

                    when {
                        // Swipe
                        distance > 50 -> {
                            ctrl.sendSwipeGesture(downX.toInt(), downY.toInt(), event.x.toInt(), event.y.toInt(), videoWidth, videoHeight, duration.coerceIn(100, 500).toInt())
                        }
                        // Double tap detection (within 300ms and close position)
                        now - lastTapTime < 300 &&
                            Math.abs(event.x - lastTapX) < 50 &&
                            Math.abs(event.y - lastTapY) < 50 -> {
                            // Double tap detected - wake up device
                            ctrl.wakeUp()
                            lastTapTime = 0
                        }
                        // Long press
                        duration > 500 -> {
                            ctrl.sendTouch(1, deviceX, deviceY, videoWidth, videoHeight, true)
                        }
                        // Tap
                        else -> {
                            ctrl.sendTouch(1, deviceX, deviceY, videoWidth, videoHeight, false)
                            lastTapTime = now
                            lastTapX = event.x
                            lastTapY = event.y
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun connectTo(host: String, port: Int) {
        btnConnect.isEnabled = false
        tvStatus.text = "连接中..."

        val ctrl = ScrcpyController(this, this)
        controller = ctrl
        ctrl.connect(host, port)
    }

    private fun disconnect() {
        controller?.disconnect()
        controller = null
        runOnUiThread {
            connectLayout.visibility = View.VISIBLE
            videoLayout.visibility = View.GONE
            btnConnect.isEnabled = true
            tvStatus.text = "已断开"
        }
    }

    // ===== ScrcpyController.Listener =====

    override fun onLog(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }

    override fun onError(msg: String) {
        runOnUiThread {
            tvStatus.text = "错误: $msg"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            btnConnect.isEnabled = true
        }
    }

    override fun onVideoSize(width: Int, height: Int) {
        videoWidth = width
        videoHeight = height
    }

    override fun onConnected() {
        runOnUiThread {
            connectLayout.visibility = View.GONE
            videoLayout.visibility = View.VISIBLE
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            connectLayout.visibility = View.VISIBLE
            videoLayout.visibility = View.GONE
            btnConnect.isEnabled = true
            tvStatus.text = "已断开"
        }
    }

    // ===== SurfaceHolder.Callback =====

    override fun surfaceCreated(holder: SurfaceHolder) {
        VideoDecoderHolder.holder = holder
        VideoDecoderHolder.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {}

    private fun attachSurface(surface: android.view.Surface) {
        // The decoder needs the surface. We store it and the controller will use it.
        // For simplicity, we recreate decoder with this surface when video size arrives.
        // Actually VideoDecoder needs surface before start; we patch via reflection or redesign.
        // Simplest: store surface globally for decoder.
        VideoDecoderHolder.surface = surface
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}

object VideoDecoderHolder {
    @Volatile var surface: android.view.Surface? = null
    @Volatile var holder: android.view.SurfaceHolder? = null
}
