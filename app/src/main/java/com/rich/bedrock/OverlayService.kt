package com.rich.bedrock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var fab: TextView
    private lateinit var mainMenu: LinearLayout
    private lateinit var contentContainer: LinearLayout
    
    private lateinit var fpsText: TextView
    private lateinit var ramText: TextView
    private lateinit var pingText: TextView

    private var crosshairView: View? = null
    private var isCrosshairVisible = false
    
    private var lastFrameTime = 0L
    private var frames = 0
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
            Toast.makeText(this, "Ошибка UI: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStats() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            if (::ramText.isInitialized) ramText.text = "${(mi.totalMem - mi.availMem) / (1024 * 1024)} MB"

            Thread {
                try {
                    val start = System.currentTimeMillis()
                    val reachable = java.net.InetAddress.getByName("8.8.8.8").isReachable(1000)
                    val time = System.currentTimeMillis() - start
                    handler.post { if (::pingText.isInitialized) pingText.text = if (reachable) "${time}ms" else "ERR" }
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
                        val fps = (frames * 1_000_000_000L / diff).roundToInt()
                        frames = 0
                        lastFrameTime = frameTimeNanos
                        if (::fpsText.isInitialized) fpsText.text = "$fps"
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
        val metrics = resources.displayMetrics
        params.x = metrics.widthPixels / 5
        params.y = metrics.heightPixels / 5

        val container = FrameLayout(this)

        // Кнопка для вызова меню
        fab = TextView(this).apply {
            text = "R"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A1A1A"))
                setStroke(4, Color.parseColor("#3DDC84"))
            }
            setOnClickListener {
                fab.visibility = View.GONE
                mainMenu.visibility = View.VISIBLE
            }
        }

        // Главное окно
        mainMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0111111"))
                cornerRadius = 30f
                setStroke(2, Color.parseColor("#333333"))
            }
            visibility = View.GONE
        }

        // Верхняя панель (Title Bar)
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadii = floatArrayOf(30f, 30f, 0f, 0f, 0f, 0f, 30f, 30f)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(TextView(this).apply {
            text = "RICH CLIENT"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        titleBar.addView(TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 24f
            setPadding(20, 0, 20, 0)
            setOnClickListener {
                mainMenu.visibility = View.GONE
                fab.visibility = View.VISIBLE
            }
        })
        titleBar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.RED)
            textSize = 20f
            setPadding(20, 0, 0, 0)
            setOnClickListener { stopSelf() }
        })

        // Перетаскивание меню за шапку
        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        initialTouchX = event.rawX; initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        mainMenu.addView(titleBar)

        // Рабочая область
        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(dp(320), dp(400))
        }

        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(100), -1)
            setPadding(0, 0, 20, 0)
        }
        
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentScroll.addView(contentContainer)

        val tabStats = createTab("Stats")
        val tabRender = createTab("Render")
        val tabMisc = createTab("Misc")

        tabStats.setOnClickListener { showStatsTab(); highlightTab(tabStats, listOf(tabRender, tabMisc)) }
        tabRender.setOnClickListener { showRenderTab(); highlightTab(tabRender, listOf(tabStats, tabMisc)) }
        tabMisc.setOnClickListener { showMiscTab(); highlightTab(tabMisc, listOf(tabStats, tabRender)) }

        tabsLayout.addView(tabStats)
        tabsLayout.addView(tabRender)
        tabsLayout.addView(tabMisc)

        bodyLayout.addView(tabsLayout)
        bodyLayout.addView(contentScroll)
        mainMenu.addView(bodyLayout)

        container.addView(fab)
        container.addView(mainMenu)
        windowManager.addView(container, params)

        showStatsTab()
        highlightTab(tabStats, listOf(tabRender, tabMisc))
    }

    private fun createTab(name: String) = TextView(this).apply {
        text = name
        setTextColor(Color.LTGRAY)
        textSize = 16f
        setPadding(20, 20, 20, 20)
        background = GradientDrawable().apply { cornerRadius = 15f; setColor(Color.TRANSPARENT) }
    }

    private fun highlightTab(selected: TextView, others: List<TextView>) {
        selected.setTextColor(Color.parseColor("#3DDC84"))
        selected.background = GradientDrawable().apply { cornerRadius = 15f; setColor(Color.parseColor("#2A2A2A")) }
        others.forEach {
            it.setTextColor(Color.LTGRAY)
            it.background = GradientDrawable().apply { cornerRadius = 15f; setColor(Color.TRANSPARENT) }
        }
    }

    private fun showStatsTab() {
        contentContainer.removeAllViews()
        fpsText = addItemRow("FPS Counter", "0").second
        ramText = addItemRow("RAM Usage", "0").second
        pingText = addItemRow("Ping", "0").second
    }

    private fun showRenderTab() {
        contentContainer.removeAllViews()
        addItemRow("Crosshair") { isChecked -> toggleCrosshair(isChecked) }
        addItemRow("No Hurt Cam") { }
    }

    private fun showMiscTab() {
        contentContainer.removeAllViews()
        addItemRow("Lock Rotation") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    val isRot = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    Settings.System.putInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (isRot) 0 else 1)
                }
            }
        }
        addItemRow("Clear RAM") {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses("com.mojang.minecraftpe")
            Toast.makeText(this, "RAM Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    // Строка с переключателем (сделан из TextView, чтобы не было ошибок компиляции)
    private fun addItemRow(name: String, initialText: String = "OFF", onChanged: ((Boolean) -> Unit)? = null): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            background = GradientDrawable().apply { cornerRadius = 15f; setColor(Color.parseColor("#1A1A1A")) }
        }

        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        val valueText = TextView(this).apply {
            text = initialText
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 14f
            setPadding(0, 0, 20, 0)
        }
        row.addView(valueText)

        if (onChanged != null) {
            var isOn = false
            val toggleBtn = TextView(this).apply {
                text = "OFF"
                setTextColor(Color.GRAY)
                textSize = 12f
                setPadding(25, 10, 25, 10)
                background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#333333")) }
                setOnClickListener {
                    isOn = !isOn
                    text = if (isOn) "ON" else "OFF"
                    setTextColor(if (isOn) Color.parseColor("#3DDC84") else Color.GRAY)
                    background = GradientDrawable().apply { cornerRadius = 20f; setColor(if (isOn) Color.parseColor("#1A3A25") else Color.parseColor("#333333")) }
                    onChanged.invoke(isOn)
                }
            }
            row.addView(toggleBtn)
        }

        contentContainer.addView(row)
        return Pair(row, valueText)
    }

    private fun toggleCrosshair(show: Boolean) {
        if (show) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.CENTER
            crosshairView = TextView(this).apply { text = "+"; setTextColor(Color.parseColor("#3DDC84")); textSize = 40f }
            windowManager.addView(crosshairView, params)
            isCrosshairVisible = true
        } else {
            crosshairView?.let { windowManager.removeView(it) }
            isCrosshairVisible = false
        }
    }

    private fun startForegroundService() {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Rich Bedrock").setContentText("Running")
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
        if (::fab.isInitialized && fab.isAttachedToWindow) windowManager.removeView(fab.parent as View)
    }
}
