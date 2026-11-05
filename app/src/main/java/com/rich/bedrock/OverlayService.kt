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
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var fab: TextView
    private lateinit var mainMenu: LinearLayout
    private lateinit var contentContainer: LinearLayout
    
    private lateinit var fpsText: TextView
    private lateinit var pingText: TextView
    private lateinit var ramText: TextView
    private lateinit var cpsText: TextView
    private lateinit var stopwatchText: TextView
    private lateinit var itemCounterView: TextView
    
    private var crosshairView: View? = null
    private var dimmerView: View? = null
    private var nightModeView: View? = null
    private var timerView: TextView? = null
    
    private var isCrosshairVisible = false
    private var isDimmerVisible = false
    private var isNightModeVisible = false
    private var isTimerVisible = false
    private var isStopwatchRunning = false
    private var isCounterVisible = false
    
    private var crosshairStyle = 0 // 0: +, 1: x, 2: .
    private var crosshairColor = "#3DDC84"
    private var cpsCount = 0
    private var itemCount = 0
    private var stopwatchSeconds = 0
    
    private var lastFrameTime = 0L
    private var frames = 0
    private var timerCountdown: CountDownTimer? = null
    
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
                // RAM
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                if (::ramText.isInitialized) ramText.text = "${(mi.totalMem - mi.availMem) / (1024 * 1024)} MB"

                // Ping
                Thread {
                    try {
                        val start = System.currentTimeMillis()
                        val reachable = java.net.InetAddress.getByName("8.8.8.8").isReachable(1000)
                        val time = System.currentTimeMillis() - start
                        handler.post { if (::pingText.isInitialized) pingText.text = if (reachable) "${time} ms" else "ERR" }
                    } catch (e: Exception) {}
                }.start()

                // Stopwatch
                if (isStopwatchRunning) {
                    stopwatchSeconds++
                    if (::stopwatchText.isInitialized) {
                        val mins = stopwatchSeconds / 60
                        val secs = stopwatchSeconds % 60
                        stopwatchText.text = String.format("%02d:%02d", mins, secs)
                    }
                }
            } catch (e: Exception) {}
            
            cpsCount = 0
            if (::cpsText.isInitialized) cpsText.text = "$cpsCount"
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
                        val fps = (frames * 1_000_000_000L / diff).toInt()
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
        params.x = 100
        params.y = 200

        val container = FrameLayout(this)

        fab = TextView(this).apply {
            text = "R"
            setTextColor(Color.WHITE)
            textSize = 22f
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

        mainMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0101010"))
                cornerRadius = 30f
                setStroke(2, Color.parseColor("#333333"))
            }
            visibility = View.GONE
        }

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
            text = "RICH PVP"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        titleBar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.RED)
            textSize = 22f
            setPadding(20, 0, 0, 0)
            setOnClickListener { stopSelf() }
        })

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

        val bodyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            layoutParams = LinearLayout.LayoutParams(dp(340), dp(420))
        }

        val tabsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(90), -1)
            setPadding(0, 0, 20, 0)
        }
        
        val contentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentScroll.addView(contentContainer)

        val tabHud = createTab("HUD")
        val tabPvp = createTab("PvP")
        val tabBedwars = createTab("Bedwars")
        val tabMacros = createTab("Macros")

        tabHud.setOnClickListener { showHudTab(); highlightTab(tabHud, listOf(tabPvp, tabBedwars, tabMacros)) }
        tabPvp.setOnClickListener { showPvpTab(); highlightTab(tabPvp, listOf(tabHud, tabBedwars, tabMacros)) }
        tabBedwars.setOnClickListener { showBedwarsTab(); highlightTab(tabBedwars, listOf(tabHud, tabPvp, tabMacros)) }
        tabMacros.setOnClickListener { showMacrosTab(); highlightTab(tabMacros, listOf(tabHud, tabPvp, tabBedwars)) }

        tabsLayout.addView(tabHud)
        tabsLayout.addView(tabPvp)
        tabsLayout.addView(tabBedwars)
        tabsLayout.addView(tabMacros)

        bodyLayout.addView(tabsLayout)
        bodyLayout.addView(contentScroll)
        mainMenu.addView(bodyLayout)

        container.addView(fab)
        container.addView(mainMenu)
        windowManager.addView(container, params)

        showHudTab()
        highlightTab(tabHud, listOf(tabPvp, tabBedwars, tabMacros))
    }

    private fun createTab(name: String) = TextView(this).apply {
        text = name
        setTextColor(Color.LTGRAY)
        textSize = 14f
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

    // --- ВКЛАДКИ ---

    private fun showHudTab() {
        contentContainer.removeAllViews()
        fpsText = createRow("FPS", "0")
        pingText = createRow("Ping", "0 ms")
        ramText = createRow("RAM", "0 MB")
        
        cpsText = createRow("CPS", "0")
        createButtonRow("CLICK ME (CPS)") {
            cpsCount++
            cpsText.text = "$cpsCount"
        }

        stopwatchText = createRow("Match Time", "00:00")
        createToggleRow("Start/Stop Time", false) { isChecked -> isStopwatchRunning = isChecked }
    }

    private fun showPvpTab() {
        contentContainer.removeAllViews()
        
        createToggleRow("Crosshair", false) { isChecked -> toggleCrosshair(isChecked) }
        
        createButtonRow("Style: + / x / .") {
            crosshairStyle = (crosshairStyle + 1) % 3
            if (isCrosshairVisible) { toggleCrosshair(false); toggleCrosshair(true) }
        }
        createButtonRow("Color: Green/Red/White") {
            crosshairColor = when(crosshairColor) {
                "#3DDC84" -> "#FF0000"
                "#FF0000" -> "#FFFFFF"
                else -> "#3DDC84"
            }
            if (isCrosshairVisible) { toggleCrosshair(false); toggleCrosshair(true) }
        }

        createToggleRow("Focus Mode", false) { isChecked -> toggleDimmer(isChecked, "#66000000") }
        createToggleRow("Night Mode", false) { isChecked -> toggleNightMode(isChecked) }
    }

    private fun showBedwarsTab() {
        contentContainer.removeAllViews()
        
        createToggleRow("Emerald Timer", false) { isChecked -> toggleTimer(isChecked, 30, "Emerald") }
        createToggleRow("Diamond Timer", false) { isChecked -> toggleTimer(isChecked, 15, "Diamond") }
        
        createButtonRow("Custom Timer (1m)") { toggleTimer(true, 60, "Custom") }
        createButtonRow("Custom Timer (3m)") { toggleTimer(true, 180, "Custom") }
        
        itemCounterView = createRow("Item Count", "0")
        createToggleRow("Show Counter", false) { isChecked -> toggleItemCounter(isChecked) }
        createButtonRow("+1 Item") { 
            itemCount++
            itemCounterView?.text = "$itemCount"
        }
        createButtonRow("-1 Item") { 
            if (itemCount > 0) itemCount--
            itemCounterView?.text = "$itemCount"
        }
    }

    private fun showMacrosTab() {
        contentContainer.removeAllViews()
        createButtonRow("Copy: /spawn") { copyToClipboard("/spawn") }
        createButtonRow("Copy: /hub") { copyToClipboard("/hub") }
        createButtonRow("Copy: GG") { copyToClipboard("GG") }
        createButtonRow("Copy: Good Luck") { copyToClipboard("Good Luck!") }
        createButtonRow("Copy: Rush") { copyToClipboard("Rushing!") }
        createButtonRow("Copy: Defend") { copyToClipboard("Defending!") }
    }

    // --- ЛОГИКА ФУНКЦИЙ ---

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Macro", text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
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
            crosshairView = TextView(this).apply { 
                text = when(crosshairStyle) {
                    0 -> "+"
                    1 -> "x"
                    else -> "•"
                }
                setTextColor(Color.parseColor(crosshairColor))
                textSize = 40f 
            }
            windowManager.addView(crosshairView, params)
            isCrosshairVisible = true
        } else {
            crosshairView?.let { windowManager.removeView(it) }
            isCrosshairVisible = false
        }
    }

    private fun toggleDimmer(show: Boolean, colorHex: String) {
        if (show) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            dimmerView = View(this).apply { setBackgroundColor(Color.parseColor(colorHex)) }
            windowManager.addView(dimmerView, params)
            isDimmerVisible = true
        } else {
            dimmerView?.let { windowManager.removeView(it) }
            isDimmerVisible = false
        }
    }

    private fun toggleNightMode(show: Boolean) {
        if (show) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            nightModeView = View(this).apply { setBackgroundColor(Color.parseColor("#33000000")) }
            windowManager.addView(nightModeView, params)
            isNightModeVisible = true
        } else {
            nightModeView?.let { windowManager.removeView(it) }
            isNightModeVisible = false
        }
    }

    private fun toggleItemCounter(show: Boolean) {
        if (show) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 100
            params.y = 300
            itemCounterView = TextView(this).apply {
                text = "Items: $itemCount"
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(20, 10, 20, 10)
                background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#AA000000")) }
            }
            windowManager.addView(itemCounterView, params)
            isCounterVisible = true
        } else {
            itemCounterView?.let { windowManager.removeView(it) }
            isCounterVisible = false
        }
    }

    private fun toggleTimer(show: Boolean, seconds: Int, name: String) {
        if (show) {
            if (timerView != null) windowManager.removeView(timerView)
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = 150
            
            timerView = TextView(this).apply {
                text = "$name: ${seconds}s"
                setTextColor(Color.WHITE)
                textSize = 20f
                setPadding(30, 15, 30, 15)
                background = GradientDrawable().apply { cornerRadius = 20f; setColor(Color.parseColor("#AA000000")) }
            }
            windowManager.addView(timerView, params)
            isTimerVisible = true
            
            timerCountdown?.cancel()
            timerCountdown = object : CountDownTimer((seconds * 1000).toLong(), 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    timerView?.text = "$name: ${millisUntilFinished / 1000}s"
                }
                override fun onFinish() {
                    timerView?.text = "$name: READY!"
                    timerView?.setTextColor(Color.parseColor("#3DDC84"))
                }
            }.start()
        } else {
            timerView?.let { windowManager.removeView(it) }
            isTimerVisible = false
            timerCountdown?.cancel()
        }
    }

    // --- UI ЭЛЕМЕНТЫ ---

    private fun createRow(name: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
            background = GradientD
