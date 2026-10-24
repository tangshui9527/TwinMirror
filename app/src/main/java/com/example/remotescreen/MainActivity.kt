package com.example.remotescreen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), ScrcpyController.Listener, SurfaceHolder.Callback {

    private lateinit var connectLayout: LinearLayout
    private lateinit var videoLayout: LinearLayout
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var etAddress: EditText
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnHome: ImageView
    private lateinit var btnRecent: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvVideoStatus: TextView
    private lateinit var navOverlay: LinearLayout
    private lateinit var videoArea: FrameLayout
    private lateinit var surfaceView: SurfaceView

    private var controller: ScrcpyController? = null
    private var videoWidth = 0
    private var videoHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        connectLayout = findViewById(R.id.connectLayout)
        videoLayout = findViewById(R.id.videoLayout)
        videoFrame = findViewById(R.id.videoFrame)
        etAddress = findViewById(R.id.etAddress)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnBack = findViewById(R.id.btnBack)
        btnHome = findViewById(R.id.btnHome)
        btnRecent = findViewById(R.id.btnRecent)
        tvStatus = findViewById(R.id.tvStatus)
        tvVideoStatus = findViewById(R.id.tvVideoStatus)
        navOverlay = findViewById(R.id.navOverlay)
        videoArea = findViewById(R.id.videoArea)
        setupNavDrag()
        surfaceView = findViewById(R.id.surfaceView)

        surfaceView.holder.addCallback(this)
        val prefs = getPreferences(Context.MODE_PRIVATE)
        etAddress.setText(prefs.getString("addr", "192.168.31.17:5555"))

        btnConnect.setOnClickListener {
            val addr = etAddress.text.toString().trim()
            if (addr.isEmpty()) {
                Toast.makeText(this, "请输入目标手机 IP:端口", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("addr", addr).apply()
            val (host, port) = parseAddress(addr)
            connectTo(host, port)
        }

        btnDisconnect.setOnClickListener { disconnect() }

        btnBack.setOnClickListener { controller?.sendKey(4) }
        btnHome.setOnClickListener { controller?.sendKey(3) }
        btnRecent.setOnClickListener { controller?.sendKey(187) }

        setupTouchForwarding()

        // Test hook: adb shell am start -n com.example.remotescreen/.MainActivity --es addr IP:PORT
        intent.getStringExtra("addr")?.trim()?.takeIf { it.isNotEmpty() }?.let { auto ->
            etAddress.setText(auto)
            val (h, p) = parseAddress(auto)
            connectTo(h, p)
        }
    }

    private val navHandler = Handler(Looper.getMainLooper())
    private val navHide = Runnable { navOverlay.visibility = View.GONE }
    private var navPlaced = false
    private var navDragGrabX = 0f
    private var navDragGrabY = 0f
    private var navDragMoved = false

    private fun showNavControls() {
        navOverlay.visibility = View.VISIBLE
        navHandler.removeCallbacks(navHide)
        navHandler.postDelayed(navHide, 3500)
        if (!navPlaced) placeNav()
    }

    /** Initial placement: last saved position, else bottom-right of the video area. */
    private fun placeNav() {
        videoArea.post {
            val aw = videoArea.width
            val ah = videoArea.height
            if (aw == 0 || ah == 0 || navPlaced) return@post
            navPlaced = true
            val maxX = (aw - navOverlay.width).coerceAtLeast(0).toFloat()
            val maxY = (ah - navOverlay.height).coerceAtLeast(0).toFloat()
            val prefs = getPreferences(Context.MODE_PRIVATE)
            val x = prefs.getInt("navx", Int.MIN_VALUE)
            val y = prefs.getInt("navy", Int.MIN_VALUE)
            navOverlay.translationX = if (x != Int.MIN_VALUE) x.toFloat().coerceIn(0f, maxX) else maxX
            navOverlay.translationY = if (y != Int.MIN_VALUE) y.toFloat().coerceIn(0f, maxY) else (maxY - 20f).coerceAtLeast(0f)
        }
    }

    /** Drag by the bar's padding/gaps; the buttons keep their own clicks. */
    private fun setupNavDrag() {
        navOverlay.isClickable = true
        navOverlay.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    navDragGrabX = e.rawX - v.translationX
                    navDragGrabY = e.rawY - v.translationY
                    navDragMoved = false
                    showNavControls()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxX = (videoArea.width - v.width).coerceAtLeast(0).toFloat()
                    val maxY = (videoArea.height - v.height).coerceAtLeast(0).toFloat()
                    val nx = (e.rawX - navDragGrabX).coerceIn(0f, maxX)
                    val ny = (e.rawY - navDragGrabY).coerceIn(0f, maxY)
                    if (Math.abs(nx - v.translationX) > 2 || Math.abs(ny - v.translationY) > 2) navDragMoved = true
                    v.translationX = nx
                    v.translationY = ny
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (navDragMoved) {
                        getPreferences(Context.MODE_PRIVATE).edit()
                            .putInt("navx", v.translationX.toInt())
                            .putInt("navy", v.translationY.toInt())
                            .apply()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setImmersiveMode(on: Boolean) {
        window.decorView.systemUiVisibility = if (on)
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        else
            View.SYSTEM_UI_FLAG_VISIBLE
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

            val sx = v.width.toFloat()
            val sy = v.height.toFloat()
            val scale = minOf(sx / videoWidth, sy / videoHeight)
            val offsetX = (sx - videoWidth * scale) / 2
            val offsetY = (sy - videoHeight * scale) / 2

            val deviceX = ((event.x - offsetX) / scale).toInt().coerceIn(0, videoWidth - 1)
            val deviceY = ((event.y - offsetY) / scale).toInt().coerceIn(0, videoHeight - 1)
            val startX = ((downX - offsetX) / scale).toInt().coerceIn(0, videoWidth - 1)
            val startY = ((downY - offsetY) / scale).toInt().coerceIn(0, videoHeight - 1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    showNavControls()
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
                        distance > 50 -> {
                            // start/end must be in the same video-frame coordinates as
                            // taps — raw view coordinates are ~3x off and land out of bounds
                            ctrl.sendSwipeGesture(
                                startX, startY, deviceX, deviceY,
                                videoWidth, videoHeight,
                                duration.coerceIn(150, 1000).toInt()
                            )
                        }
                        now - lastTapTime < 300 &&
                            Math.abs(event.x - lastTapX) < 50 &&
                            Math.abs(event.y - lastTapY) < 50 -> {
                            ctrl.wakeUp()
                            lastTapTime = 0
                        }
                        duration > 500 -> {
                            ctrl.sendTouch(deviceX, deviceY, videoWidth, videoHeight, true)
                        }
                        else -> {
                            ctrl.sendTouch(deviceX, deviceY, videoWidth, videoHeight, false)
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
        controller?.disconnect()
        controller = null
        btnConnect.isEnabled = false
        tvStatus.text = "连接中..."
        val ctrl = ScrcpyController(this, this)
        controller = ctrl
        ctrl.connect(host, port)
    }

    private fun disconnect() {
        controller?.disconnect()
        controller = null
        navHandler.removeCallbacks(navHide)
        runOnUiThread {
            connectLayout.visibility = View.VISIBLE
            videoLayout.visibility = View.GONE
            videoLayout.keepScreenOn = false
            setImmersiveMode(false)
            btnConnect.isEnabled = true
            tvStatus.text = "已断开"
        }
    }

    override fun onLog(msg: String) {
        runOnUiThread {
            tvStatus.text = msg
            if (controller?.isRunning() == true) {
                tvVideoStatus.visibility = View.VISIBLE
                tvVideoStatus.text = msg
            }
        }
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
        videoFrame.post {
            videoFrame.aspectRatio = width.toFloat() / height
            videoFrame.requestLayout()
        }
        // The H.264 decoder fires this with real stream dimensions on its first frame;
        // the fallback screenshot path reports the wm size right after connect. Either
        // way the status overlay has done its job once dimensions are known.
        runOnUiThread { tvVideoStatus.visibility = View.GONE }
    }

    override fun onConnected() {
        runOnUiThread {
            connectLayout.visibility = View.GONE
            videoLayout.visibility = View.VISIBLE
            // Controlling a remote screen with the local screen off is useless
            videoLayout.keepScreenOn = true
            setImmersiveMode(true)
            showNavControls()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            connectLayout.visibility = View.VISIBLE
            videoLayout.visibility = View.GONE
            tvVideoStatus.visibility = View.GONE
            videoLayout.keepScreenOn = false
            navHandler.removeCallbacks(navHide)
            setImmersiveMode(false)
            btnConnect.isEnabled = true
            tvStatus.text = "已断开"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        VideoDecoderHolder.holder = holder
        VideoDecoderHolder.surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        VideoDecoderHolder.surface = null
        VideoDecoderHolder.holder = null
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
