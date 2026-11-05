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
    
    private lateinit var fpsText: TextView
    private lateinit var ramText: TextView

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
            e.printStackTrace()
        }
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            try {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (::ramText.isInitialized) ramText.text = "${(mi.totalMem - mi.availMem) / (1024 * 1024)} MB"
            } catch (e: Exception) {}
            handler.postDelayed(this, 1000)
        }
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
        params.x = 150
        params.y = 200

        val container = FrameLayout(this)

        // Кнопка "R"
        fab = TextView(this)
        fab.text = "R"
        fab.setTextColor(Color.WHITE)
        fab.textSize = 22f
        fab.gravity = Gravity.CENTER
        fab.setPadding(30, 30, 30, 30)
        val fabBg = GradientDrawable()
        fabBg.shape = GradientDrawable.OVAL
        fabBg.setColor(Color.parseColor("#1A1A1A"))
        fabBg.setStroke(4, Color.parseColor("#3DDC84"))
        fab.background = fabBg
        fab.setOnClickListener {
            fab.visibility = View.GONE
            mainMenu.visibility = View.VISIBLE
        }

        // Главное меню
        mainMenu = LinearLayout(this)
        mainMenu.orientation = LinearLayout.VERTICAL
        val menuBg = GradientDrawable()
        menuBg.setColor(Color.parseColor("#F0101010"))
        menuBg.cornerRadius = 30f
        menuBg.setStroke(2, Color.parseColor("#333333"))
        mainMenu.background = menuBg
        mainMenu.visibility = View.GONE

        // Шапка (Title Bar)
        val titleBar = LinearLayout(this)
        titleBar.orientation = LinearLayout.HORIZONTAL
        titleBar.setPadding(30, 20, 30, 20)
        val titleBg = GradientDrawable()
        titleBg.setColor(Color.parseColor("#1E1E1E"))
        titleBg.cornerRadii = floatArrayOf(30f, 30f, 0f, 0f, 0f, 0f, 30f, 30f)
        titleBar.background = titleBg
        titleBar.gravity = Gravity.CENTER_VERTICAL

        val titleText = TextView(this)
        titleText.text = "RICH CLIENT"
        titleText.setTextColor(Color.parseColor("#3DDC84"))
        titleText.textSize = 16f
        titleText.setTypeface(null, android.graphics.Typeface.BOLD)
        titleText.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        titleBar.addView(titleText)

        val closeBtn = TextView(this)
        closeBtn.text = "✕"
        closeBtn.setTextColor(Color.RED)
        closeBtn.textSize = 22f
        closeBtn.setPadding(20, 0, 0, 0)
        closeBtn.setOnClickListener { stopSelf() }
        titleBar.addView(closeBtn)

        // Перетаскивание меню
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

        // Тело меню
        val bodyLayout = LinearLayout(this)
        bodyLayout.orientation = LinearLayout.HORIZONTAL
        bodyLayout.setPadding(20, 20, 20, 20)
        bodyLayout.layoutParams = LinearLayout.LayoutParams(dp(320), dp(350))

        // Левая колонка (Вкладки)
        val tabsLayout = LinearLayout(this)
        tabsLayout.orientation = LinearLayout.VERTICAL
        tabsLayout.layoutParams = LinearLayout.LayoutParams(dp(90), -1)
        tabsLayout.setPadding(0, 0, 20, 0)
        
        // Правая колонка (Контент)
        val contentScroll = ScrollView(this)
        contentScroll.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        contentContainer = LinearLayout(this)
        contentContainer.orientation = LinearLayout.VERTICAL
        contentScroll.addView(contentContainer)

        // Вкладки
        val tabStats = TextView(this)
        tabStats.text = "Stats"
        tabStats.textSize = 14f
        tabStats.setPadding(20, 20, 20, 20)

        val tabRender = TextView(this)
        tabRender.text = "Render"
        tabRender.textSize = 14f
        tabRender.setPadding(20, 20, 20, 20)

        tabStats.setOnClickListener { 
            showStatsTab()
            tabStats.setTextColor(Color.parseColor("#3DDC84"))
            val bg = GradientDrawable(); bg.cornerRadius = 15f; bg.setColor(Color.parseColor("#2A2A2A")); tabStats.background = bg
            tabRender.setTextColor(Color.LTGRAY)
            val bg2 = GradientDrawable(); bg2.cornerRadius = 15f; bg2.setColor(Color.TRANSPARENT); tabRender.background = bg2
        }
        
        tabRender.setOnClickListener { 
            showRenderTab()
            tabRender.setTextColor(Color.parseColor("#3DDC84"))
            val bg = GradientDrawable(); bg.cornerRadius = 15f; bg.setColor(Color.parseColor("#2A2A2A")); tabRender.background = bg
            tabStats.setTextColor(Color.LTGRAY)
            val bg2 = GradientDrawable(); bg2.cornerRadius = 15f; bg2.setColor(Color.TRANSPARENT); tabStats.background = bg2
        }

        tabsLayout.addView(tabStats)
        tabsLayout.addView(tabRender)

        bodyLayout.addView(tabsLayout)
        bodyLayout.addView(contentScroll)
        mainMenu.addView(bodyLayout)

        container.addView(fab)
        container.addView(mainMenu)
        windowManager.addView(container, params)

        // Стартуем со вкладки Stats
        showStatsTab()
        tabStats.setTextColor(Color.parseColor("#3DDC84"))
        val bg = GradientDrawable(); bg.cornerRadius = 15f; bg.setColor(Color.parseColor("#2A2A2A")); tabStats.background = bg
    }

    private fun showStatsTab() {
        contentContainer.removeAllViews()
        fpsText = createRow("FPS", "0")
        ramText = createRow("RAM", "0")
    }

    private fun showRenderTab() {
        contentContainer.removeAllViews()
        createToggleRow("Crosshair") { isChecked -> toggleCrosshair(isChecked) }
        createToggleRow("No Hurt Cam") { }
    }

    // Создает простую строку (для Статистики)
    private fun createRow(name: String, value: String): TextView {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(20, 20, 20, 20)
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        val rowBg = GradientDrawable(); rowBg.cornerRadius = 15f; rowBg.setColor(Color.parseColor("#1A1A1A")); row.background = rowBg

        val nameText = TextView(this)
        nameText.text = name
        nameText.setTextColor(Color.WHITE)
        nameText.textSize = 14f
        nameText.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(nameText)

        val valueText = TextView(this)
        valueText.text = value
        valueText.setTextColor(Color.parseColor("#3DDC84"))
        valueText.textSize = 14f
        row.addView(valueText)

        contentContainer.addView(row)
        return valueText
    }

    // Создает строку с кнопкой ВКЛ/ВЫКЛ (для Рендера)
    private fun createToggleRow(name: String, onChanged: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(20, 20, 20, 20)
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        val rowBg = GradientDrawable(); rowBg.cornerRadius = 15f; rowBg.setColor(Color.parseColor("#1A1A1A")); row.background = rowBg

        val nameText = TextView(this)
        nameText.text = name
        nameText.setTextColor(Color.WHITE)
        nameText.textSize = 14f
        nameText.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        row.addView(nameText)

        var isOn = false
        val toggleBtn = TextView(this)
        toggleBtn.text = "OFF"
        toggleBtn.setTextColor(Color.GRAY)
        toggleBtn.textSize = 12f
        toggleBtn.setPadding(25, 10, 25, 10)
        val btnBg = GradientDrawable(); btnBg.cornerRadius = 20f; btnBg.setColor(Color.parseColor("#333333")); toggleBtn.background = btnBg
        
        toggleBtn.setOnClickListener {
            isOn = !isOn
            toggleBtn.text = if (isOn) "ON" else "OFF"
            toggleBtn.setTextColor(if (isOn) Color.parseColor("#3DDC84") else Color.GRAY)
            val b = GradientDrawable(); b.cornerRadius = 20f; b.setColor(if (isOn) Color.parseColor("#1A3A25") else Color.parseColor("#333333")); toggleBtn.background = b
            onChanged.invoke(isOn)
        }
        row.addView(toggleBtn)
        contentContainer.addView(row)
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
            crosshairView = TextView(this)
            crosshairView?.let {
                (it as TextView).text = "+"
                (it as TextView).setTextColor(Color.parseColor("#3DDC84"))
                (it as TextView).textSize = 40f
            }
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

    private fun dp(v: Int): Int {
        return (v * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statsUpdater)
        if (isCrosshairVisible) crosshairView?.let { windowManager.removeView(it) }
        if (::fab.isInitialized && fab.isAttachedToWindow) windowManager.removeView(fab.parent as View)
    }
}
