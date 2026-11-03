package com.rich.bedrock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var fab: TextView
    private lateinit var menuContainer: LinearLayout
    private lateinit var fpsText: TextView
    private lateinit var ramText: TextView
    private lateinit var batteryText: TextView
    private lateinit var pingText: TextView
    private lateinit var cpsText: TextView
    private var crosshairView: View? = null
    private var dimmerView: View? = null
    private var isCrosshairVisible = false
    private var isDimmerVisible = false
    private var isFlashlightOn = false
    private var lastFrameTime = 0L
    private var frames = 0
    private var cpsCount = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            createOverlay()
            handler.post(statsUpdater)
            startFpsCounter()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка меню: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            updateStats()
            cpsCount = 0
            if (::cpsText.isInitialized) cpsText.text = "CPS: 0"
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStats() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (::ramText.isInitialized) ramText.text = "RAM: ${(mi.totalMem - mi.availMem) / (1024 * 1024)}/${mi.totalMem / (1024 * 1024)} MB"
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            if (::batteryText.isInitialized) batteryText.text = "Bat: ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
            Thread {
                try {
                    val start = System.currentTimeMillis()
                    val reachable = java.net.InetAddress.getByName("8.8.8.8").isReachable(1000)
                    val time = System.currentTimeMillis() - start
                    handler.post {
                        if (::pingText.isInitialized) pingText.text = if (reachable) "Ping: ${time}ms" else "Ping: Error"
                    }
                } catch (e: Exception) {}
            }.start()
        } catch (e: Exception) {}
    }

    private fun startFpsCounter() {
        val choreographer = Choreographer.getInstance()
        val frameCallback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTime != 0L) {
                    val diff = frameTimeNanos - lastFrameTime
                    frames++
                    if (diff >= 1_000_000_000L) {
                        val fps = (frames * 1_000_000_000L / diff).toInt()
                        frames = 0
                        lastFrameTime = frameTimeNanos
                        if (::fpsText.isInitialized) fpsText.text = "FPS: $fps"
                    }
                } else {
                    lastFrameTime = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(frameCallback)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val displayMetrics = resources.displayMetrics
        params.x = displayMetrics.widthPixels / 2 - 100
        params.y = displayMetrics.heightPixels / 2 - 100

        val container = FrameLayout(this)

        fab = TextView(this).apply {
            text = "R"
            setTextColor(Color.BLACK)
            textSize = 24f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#3DDC84"))
                setStroke(6, Color.WHITE)
            }
            setOnClickListener {
                fab.visibility = View.GONE
                menuContainer.visibility = View.VISIBLE
                updateStats()
            }
        }

        menuContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F01E1E1E"))
                cornerRadius = 60f
            }
            visibility = View.GONE
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(280), dp(450))
        }
        val menuLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        menuLayout.addView(TextView(this).apply {
            text = "Rich Bedrock"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        })

        fpsText = createStatText()
        ramText = createStatText()
        batteryText = createStatText()
        pingText = createStatText()
        
        menuLayout.addView(fpsText)
        menuLayout.addView(ramText)
        menuLayout.addView(batteryText)
        menuLayout.addView(pingText)

        cpsText = TextView(this).apply {
            text = "CPS: 0"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 10)
        }
        menuLayout.addView(cpsText)
        
        menuLayout.addView(createBtn("Тест CPS") { cpsCount++; cpsText.text = "CPS: $cpsCount" })
        menuLayout.addView(createBtn("Сбросить RAM") {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses("com.mojang.minecraftpe")
            Toast.makeText(this, "Память очищена", Toast.LENGTH_SHORT).show()
        })
        menuLayout.addView(createBtn("Прицел") { toggleCrosshair() })
        menuLayout.addView(createBtn("Затемнить экран") { toggleDimmer() })
        menuLayout.addView(createBtn("Фонарик") { toggleFlashlight() })
        menuLayout.addView(createBtn("Блокировка поворота") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    val isRot = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (isRot) 0 else 1)
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        })
        menuLayout.addView(createBtn("Wiki") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://minecraft.wiki")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        menuLayout.addView(createBtn("Карта сидов") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chunkbase.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        menuLayout.addView(createBtn("Копировать Координаты") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Coords", "X: 0 Y: 0 Z: 0"))
            Toast.makeText(this, "Скопировано!", Toast.LENGTH_SHORT).show()
        })
        menuLayout.addView(createBtn("Закрыть оверлей") { stopSelf() })

        scroll.addView(menuLayout)
        menuContainer.addView(scroll)

        val closeMenuBtn = TextView(this).apply {
            text = "X"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.RED)
            }
            setOnClickListener {
                fab.visibility = View.VISIBLE
                menuContainer.visibility = View.GONE
            }
        }
        val closeParams = FrameLayout.LayoutParams(dp(50), dp(50)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = -20
            marginEnd = -20
        }
        container.addView(closeMenuBtn, closeParams)
        container.addView(fab)
        container.addView(menuContainer)

        fab.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.rawX - initialTouchX) < 20 && Math.abs(event.rawY - initialTouchY) < 20) {
                            v?.performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
        windowManager.addView(container, params)
    }

    private fun createStatText() = TextView(this).apply {
        setTextColor(Color.LTGRAY); textSize = 16f; setPadding(0, 5, 0, 5)
    }

    private fun createBtn(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(20, 30, 20, 30)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#333333"))
            cornerRadius = 30f
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
        setOnClickListener { onClick() }
    }

    private fun toggleCrosshair() {
        if (isCrosshairVisible) { crosshairView?.let { windowManager.removeView(it) }; isCrosshairVisible = false } 
        else {
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.CENTER
            crosshairView = TextView(this).apply { text = "+"; setTextColor(Color.parseColor("#3DDC84")); textSize = 40f }
            windowManager.addView(crosshairView, params); isCrosshairVisible = true
        }
    }

    private fun toggleDimmer() {
        if (isDimmerVisible) { dimmerView?.let { windowManager.removeView(it) }; isDimmerVisible = false } 
        else {
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT)
            dimmerView = View(this).apply { setBackgroundColor(Color.parseColor("#99000000")) }
            windowManager.addView(dimmerView, params); isDimmerVisible = true
        }
    }

    private fun toggleFlashlight() {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager.setTorchMode(cameraManager.cameraIdList[0], !isFlashlightOn)
            isFlashlightOn = !isFlashlightOn
        } catch (e: Exception) { Toast.makeText(this, "Ошибка фонарика", Toast.LENGTH_SHORT).show() }
    }

    private fun startForegroundService() {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rich Bedrock активен").setContentText("Меню загружено")
            .setSmallIcon(android.R.drawable.ic_menu_compass).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
        } catch (e: Exception) { startForeground(1, notification) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statsUpdater)
        if (isCrosshairVisible) crosshairView?.let { windowManager.removeView(it) }
        if (isDimmerVisible) dimmerView?.let { windowManager.removeView(it) }
        if (::fab.isInitialized && fab.isAttachedToWindow) windowManager.removeView(fab.parent as View)
    }
}
