package com.rich.bedrock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var fab: TextView
    private lateinit var mainMenu: LinearLayout
    private lateinit var contentContainer: LinearLayout
    
    // Тексты для статистики
    private lateinit var fpsText: TextView
    private lateinit var ramText: TextView
    private lateinit var pingText: TextView

    private var crosshairView: View? = null
    private var isCrosshairVisible = false
    private var isFlashlightOn = false
    
    private var lastFrameTime = 0L
    private var frames = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        handler.post(statsUpdater)
        startFpsCounter()
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
                    handler.post { if (::pingText.isInitialized) pingText.text = if (reachable) "${time} ms" else "Error" }
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
        params.x = metrics.widthPixels / 4
        params.y = metrics.heightPixels / 4

        val container = FrameLayout(this)

        // Кнопка-иконка для открытия меню (когда оно свернуто)
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

        // Главное меню (в стиле ImGui)
        mainMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0111111")) // Темный фон
                cornerRadius = 20f
                setStroke(2, Color.parseColor("#333333"))
            }
            visibility = View.GONE
        }

        // 1. Верхняя панель (Title Bar)
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 20, 30, 20)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadii = floatArrayOf(20f, 20f, 0f, 0f, 0f, 0f, 20f, 20f)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        titleBar.addView(TextView(this).apply {
            text = "Rich Client"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        // Кнопка свернуть
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
        // Кнопка закрыть
        titleBar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.RED)
            textSize = 20f
            setPadding(20, 0, 0, 0)
            setOnClickListener { stopSelf() }
        })

        // Перетаскивание окна за верхнюю панель
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

        // 2. Рабочая область (Слева вкладки, Справа контент)
        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(dp(320), dp(400))
        }

        // Левая панель вкладок
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

        // Создаем вкладки
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

        // Открываем вкладку Stats по умолчанию
        showStatsTab()
        highlightTab(tabStats, listOf(tabRender, tabMisc))
    }

    // --- ВКЛАДКИ И КОНТЕНТ ---

    private fun createTab(name: String) = TextView(this).apply {
        text = name
        setTextColor(Color.LTGRAY)
        textSize = 16f
        setPadding(20, 20, 20, 20)
        background = GradientDrawable().apply { cornerRadius = 10f; setColor(Color.TRANSPARENT) }
    }

    private fun highlightTab(selected: TextView, others: List<TextView>) {
        selected.setTextColor(Color.parseColor("#3DDC84"))
        selected.background = GradientDrawable().apply { cornerRadius = 10f; setColor(Color.parseColor("#2A2A2A")) }
        others.forEach {
            it.setTextColor(Color.LTGRAY)
            it.background = GradientDrawable().apply { cornerRadius = 10f; setColor(Color.TRANSPARENT) }
        }
    }

    private fun showStatsTab() {
        contentContainer.removeAllViews()
        fpsText = addToggleRow("FPS Counter", true).second
        ramText = addToggleRow("RAM Usage", true).second
        pingText = addToggleRow("Ping", true).second
    }

    private fun showRenderTab() {
        contentContainer.removeAllViews()
        addToggleRow("Crosshair", false) { isChecked ->
            toggleCrosshair(isChecked)
        }
        addToggleRow("Fullbright (Dev)", false)
        addToggleRow("No Hurt Cam", false)
    }

    private fun showMiscTab() {
        contentContainer.removeAllViews()
        addToggleRow("Flashlight", false) { isChecked ->
            toggleFlashlight(isChecked)
        }
        addButtonRow("Clear RAM") {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses("com.mojang.minecraftpe")
            Toast.makeText(this, "RAM Cleared", Toast.LENGTH_SHORT).show()
        }
        addButtonRow("Lock Rotation") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (android.provider.Settings.System.canWrite(this)) {
                    val isRot = android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, if (isRot) 0 else 1)
                }
            }
        }
    }

    // Создает строку с названием и переключателем
    private fun addToggleRow(name: String, initialState: Boolean, onChanged: ((Boolean) -> Unit)? = null): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 20, 10, 20)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            background = GradientDrawable().apply { cornerRadius = 10f; setColor(Color.parseColor("#1A1A1A")) }
        }

        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        // Текстовое поле для отображения значения (для FPS/RAM)
        val valueText = TextView(this).apply {
            text = if (initialState && name == "FPS Counter" || name == "RAM Usage" || name == "Ping") "0" else ""
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 14f
            setPadding(0, 0, 20, 0)
        }
        row.addView(valueText)

        val toggle = Switch(this).apply {
            isChecked = initialState
            thumbTintList = android.content.res.ColorStateList.valueOf(if (initialState) Color.parseColor("#3DDC84") else Color.GRAY)
            trackTintList = android.content.res.ColorStateList.valueOf(if (initialState) Color.parseColor("#1A3A25") else Color.parseColor("#333333"))
            setOnCheckedChangeListener { _, isChecked ->
                thumbTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Color.parseColor("#3DDC84") else Color.GRAY)
                trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Color.parseColor("#1A3A25") else Color.parseColor("#333333"))
                onChanged?.invoke(isChecked)
            }
        }
        
        // Если это просто стат-строка, скрываем переключатель
        if (onChanged == null) {
            toggle.visibility = View.GONE
        } else {
            row.addView(toggle)
        }

        contentContainer.addView(row)
        return Pair(row, valueText)
    }

    private fun addButtonRow(name: String, onClick: () -> Unit) {
        val btn = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(20, 30, 20, 30)
            background = GradientDrawable().apply { cornerRadius = 10f; setColor(Color.parseColor("#2A2A2A")) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 15 }
            setOnClickListener { onClick() }
        }
        contentContainer.addView(btn)
    }

    // --- ФУНКЦИИ ---

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

    private fun toggleFlashlight(on: Boolean) {
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager.setTorchMode(cameraManager.cameraIdList[0], on)
            isFlashlightOn = on
        } catch (e: Exception) {}
    }

    private fun startForegroundService() {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Overlay Service", NotificationManager.IMPORTANCE_LOW)
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
