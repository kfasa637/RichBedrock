package com.rich.bedrock

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import kotlin.concurrent.thread

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var fab: TextView
    private lateinit var mainMenu: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var rootOverlay: FrameLayout

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
    private var floatingCounterView: TextView? = null

    private var isCrosshairVisible = false
    private var isDimmerVisible = false
    private var isNightModeVisible = false
    private var isStopwatchRunning = false
    private var isCounterVisible = false
    private var bedDestroyed = false

    private var crosshairStyle = 0
    private var crosshairColor = "#3DDC84"
    private var cpsCount = 0
    private var itemCount = 0
    private var stopwatchSeconds = 0

    private var lastFrameTime = 0L
    private var frames = 0
    private var fpsCallback: Choreographer.FrameCallback? = null
    private var timerCountdown: CountDownTimer? = null
    private var timerEndAt = 0L
    private var timerLabel = "Timer"

    private val resourceCounts = linkedMapOf(
        "Iron" to 0,
        "Gold" to 0,
        "Diamond" to 0,
        "Emerald" to 0
    )

    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlay()
        handler.post(statsUpdater)
        startFpsCounter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startAsForeground() {
        val channelId = "rich_bedrock_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Rich Bedrock overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Rich Bedrock работает")
            .setContentText("Игровая панель поверх Minecraft")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    private val statsUpdater = object : Runnable {
        override fun run() {
            try {
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                if (::ramText.isInitialized) {
                    val usedMb = (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
                    ramText.text = "$usedMb MB"
                }

                thread(name = "RichBedrockPing") {
                    val start = System.currentTimeMillis()
                    val result = try {
                        InetAddress.getByName("1.1.1.1").isReachable(700)
                    } catch (_: Exception) {
                        false
                    }
                    val elapsed = System.currentTimeMillis() - start
                    handler.post {
                        if (::pingText.isInitialized) {
                            pingText.text = if (result) "$elapsed ms" else "—"
                        }
                    }
                }

                if (isStopwatchRunning) {
                    stopwatchSeconds++
                    if (::stopwatchText.isInitialized) {
                        stopwatchText.text = formatTime(stopwatchSeconds)
                    }
                }
            } catch (_: Exception) {
            }

            if (::cpsText.isInitialized) cpsText.text = cpsCount.toString()
            cpsCount = 0
            handler.postDelayed(this, 1000L)
        }
    }

    private fun startFpsCounter() {
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (lastFrameTime != 0L) {
                    frames++
                    val elapsed = frameTimeNanos - lastFrameTime
                    if (elapsed >= 1_000_000_000L) {
                        val fps = (frames * 1_000_000_000L / elapsed).toInt()
                        frames = 0
                        lastFrameTime = frameTimeNanos
                        if (::fpsText.isInitialized) fpsText.text = "~$fps"
                    }
                } else {
                    lastFrameTime = frameTimeNanos
                }
                choreographer.postFrameCallback(this)
            }
        }
        fpsCallback = callback
        choreographer.postFrameCallback(callback)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlay() {
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(18)
            y = dp(130)
        }

        rootOverlay = FrameLayout(this)

        val fabBackground = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#00FF87"), Color.parseColor("#00A854"))
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), Color.WHITE)
        }

        fab = TextView(this).apply {
            text = "R"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = fabBackground
            setOnClickListener {
                fab.visibility = View.GONE
                mainMenu.visibility = View.VISIBLE
            }
        }

        mainMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#F0101010", "#3DDC84", 2, 24)
            visibility = View.GONE
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded("#1E1E1E", "#1E1E1E", 0, 20)
            gravity = Gravity.CENTER_VERTICAL
        }

        titleBar.addView(TextView(this).apply {
            text = "RICH BEDROCK"
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        titleBar.addView(TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                mainMenu.visibility = View.GONE
                fab.visibility = View.VISIBLE
            }
        })

        titleBar.addView(TextView(this).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 24f
            setOnClickListener { stopSelf() }
        })

        titleBar.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(view: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = overlayParams.x
                        startY = overlayParams.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        overlayParams.x = startX + (event.rawX - touchX).toInt()
                        overlayParams.y = startY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(rootOverlay, overlayParams)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                }
                return false
            }
        })
        mainMenu.addView(titleBar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(340), dp(430))
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(88), -1)
            setPadding(0, 0, dp(10), 0)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            isFillViewport = true
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(contentContainer)

        val hudTab = createTab("HUD")
        val pvpTab = createTab("PvP")
        val bedwarsTab = createTab("Bedwars")
        val macrosTab = createTab("Macros")

        hudTab.setOnClickListener {
            showHudTab()
            highlightTab(hudTab, listOf(pvpTab, bedwarsTab, macrosTab))
        }
        pvpTab.setOnClickListener {
            showPvpTab()
            highlightTab(pvpTab, listOf(hudTab, bedwarsTab, macrosTab))
        }
        bedwarsTab.setOnClickListener {
            showBedwarsTab()
            highlightTab(bedwarsTab, listOf(hudTab, pvpTab, macrosTab))
        }
        macrosTab.setOnClickListener {
            showMacrosTab()
            highlightTab(macrosTab, listOf(hudTab, pvpTab, bedwarsTab))
        }

        tabs.addView(hudTab)
        tabs.addView(pvpTab)
        tabs.addView(bedwarsTab)
        tabs.addView(macrosTab)
        body.addView(tabs)
        body.addView(scroll)
        mainMenu.addView(body)

        rootOverlay.addView(fab)
        rootOverlay.addView(mainMenu)
        windowManager.addView(rootOverlay, overlayParams)

        showHudTab()
        highlightTab(hudTab, listOf(pvpTab, bedwarsTab, macrosTab))
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(
        fill: String,
        stroke: String = fill,
        strokeWidth: Int = 0,
        radiusDp: Int = 14
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), Color.parseColor(stroke))
        }
    }

    private fun createTab(name: String): TextView = TextView(this).apply {
        text = name
        setTextColor(Color.LTGRAY)
        textSize = 13f
        setPadding(dp(10), dp(14), dp(6), dp(14))
        background = rounded("#00000000")
    }

    private fun highlightTab(selected: TextView, others: List<TextView>) {
        selected.setTextColor(Color.parseColor("#3DDC84"))
        selected.background = rounded("#2A2A2A")
        others.forEach {
            it.setTextColor(Color.LTGRAY)
            it.background = rounded("#00000000")
        }
    }

    private fun createRow(name: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
            background = rounded("#1A1A1A")
        }
        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val valueText = TextView(this).apply {
            text = value
            setTextColor(Color.parseColor("#3DDC84"))
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(valueText)
        contentContainer.addView(row)
        return valueText
    }

    private fun createButtonRow(name: String, onClick: () -> Unit) {
        val button = TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(13), dp(10), dp(13))
            background = rounded("#292929")
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
            setOnClickListener { onClick() }
        }
        contentContainer.addView(button)
    }

    private fun createToggleRow(
        name: String,
        initialState: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            }
            background = rounded("#1A1A1A")
        }
        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        var enabled = initialState
        val toggle = TextView(this).apply {
            text = if (enabled) "ON" else "OFF"
            setTextColor(if (enabled) Color.parseColor("#3DDC84") else Color.GRAY)
            textSize = 12f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = rounded(if (enabled) "#1A3A25" else "#333333", radiusDp = 20)
            setOnClickListener {
                enabled = !enabled
                text = if (enabled) "ON" else "OFF"
                setTextColor(if (enabled) Color.parseColor("#3DDC84") else Color.GRAY)
                background = rounded(
                    if (enabled) "#1A3A25" else "#333333",
                    radiusDp = 20
                )
                onChanged(enabled)
            }
        }
        row.addView(toggle)
        contentContainer.addView(row)
    }

    private fun showHudTab() {
        contentContainer.removeAllViews()
        fpsText = createRow("Display FPS (approx.)", "—")
        pingText = createRow("Network ping (approx.)", "—")
        ramText = createRow("System RAM used", "—")
        cpsText = createRow("Manual CPS counter", "0")
        createButtonRow("TAP — count your clicks") {
            cpsCount++
            if (::cpsText.isInitialized) cpsText.text = cpsCount.toString()
        }
        stopwatchText = createRow("Match stopwatch", formatTime(stopwatchSeconds))
        createToggleRow("Run match stopwatch", isStopwatchRunning) {
            isStopwatchRunning = it
        }
        createButtonRow("Reset stopwatch") {
            stopwatchSeconds = 0
            if (::stopwatchText.isInitialized) stopwatchText.text = "00:00"
        }
        createButtonRow("Reset CPS counter") {
            cpsCount = 0
            if (::cpsText.isInitialized) cpsText.text = "0"
        }
        createRow("Note", "FPS / ping are estimates")
    }

    private fun showPvpTab() {
        contentContainer.removeAllViews()
        createToggleRow("Crosshair", isCrosshairVisible) {
            toggleCrosshair(it)
        }
        createButtonRow("Crosshair style: + / × / •") {
            crosshairStyle = (crosshairStyle + 1) % 3
            if (isCrosshairVisible) {
                toggleCrosshair(false)
                toggleCrosshair(true)
            }
        }
        createButtonRow("Crosshair color: green / red / white") {
            crosshairColor = when (crosshairColor) {
                "#3DDC84" -> "#FF5555"
                "#FF5555" -> "#FFFFFF"
                else -> "#3DDC84"
            }
            if (isCrosshairVisible) {
                toggleCrosshair(false)
                toggleCrosshair(true)
            }
        }
        createToggleRow("Focus dimmer", isDimmerVisible) {
            toggleDimmer(it, "#55000000")
        }
        createToggleRow("Blue-light filter", isNightModeVisible) {
            toggleNightMode(it)
        }
        createRow("Practice tip", "Track aim; avoid rushing")
        createRow("Fair-play", "No auto-click or aim-bot")
    }

    private fun showBedwarsTab() {
        contentContainer.removeAllViews()

        createRow("GENERATOR TIMERS", "Manual")
        createButtonRow("Diamond timer (15s preset)") {
            toggleTimer(true, 15, "Diamond")
        }
        createButtonRow("Emerald timer (30s preset)") {
            toggleTimer(true, 30, "Emerald")
        }
        createButtonRow("Start 15-second timer") {
            toggleTimer(true, 15, "Generator")
        }
        createButtonRow("Start 30-second timer") {
            toggleTimer(true, 30, "Generator")
        }
        createButtonRow("Start 1-minute timer") {
            toggleTimer(true, 60, "Generator")
        }
        createButtonRow("Start 3-minute timer") {
            toggleTimer(true, 180, "Generator")
        }
        createButtonRow("Stop timer") {
            toggleTimer(false, 0, "Timer")
        }

        createRow("RESOURCE TRACKER", "Manual")
        resourceCounts.keys.forEach { resource ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(6)
                }
                background = rounded("#1A1A1A")
            }
            row.addView(TextView(this).apply {
                text = resource
                setTextColor(Color.WHITE)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            val countText = TextView(this).apply {
                text = resourceCounts[resource].toString()
                setTextColor(Color.parseColor("#3DDC84"))
                textSize = 13f
                setPadding(dp(8), 0, dp(8), 0)
            }
            val minus = smallCounterButton("−") {
                resourceCounts[resource] = ((resourceCounts[resource] ?: 0) - 1).coerceAtLeast(0)
                countText.text = resourceCounts[resource].toString()
            }
            val plus = smallCounterButton("+") {
                resourceCounts[resource] = (resourceCounts[resource] ?: 0) + 1
                countText.text = resourceCounts[resource].toString()
            }
            row.addView(minus)
            row.addView(countText)
            row.addView(plus)
            contentContainer.addView(row)
        }

        itemCounterView = createRow("Items collected", itemCount.toString())
        createButtonRow("+1 item") {
            itemCount++
            itemCounterView.text = itemCount.toString()
            updateFloatingCounter()
        }
        createButtonRow("−1 item") {
            itemCount = (itemCount - 1).coerceAtLeast(0)
            itemCounterView.text = itemCount.toString()
            updateFloatingCounter()
        }
        createToggleRow("Floating item counter", isCounterVisible) {
            toggleItemCounter(it)
        }

        createToggleRow("Our bed is destroyed", bedDestroyed) {
            bedDestroyed = it
            Toast.makeText(
                this,
                if (it) "Bed status: destroyed" else "Bed status: safe",
                Toast.LENGTH_SHORT
            ).show()
        }
        createButtonRow("Reset resource and item counters") {
            resourceCounts.keys.forEach { key ->
    resourceCounts[key] = 0
            }
            itemCount = 0
            showBedwarsTab()
        }
        createRow("Reminder", "Timers are started manually")
    }

    private fun smallCounterButton(label: String, action: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = rounded("#303030", radiusDp = 8)
            setOnClickListener { action() }
        }
    }

    private fun showMacrosTab() {
        contentContainer.removeAllViews()
        createButtonRow("Copy: /spawn") { copyToClipboard("/spawn") }
        createButtonRow("Copy: /hub") { copyToClipboard("/hub") }
        createButtonRow("Copy: GG") { copyToClipboard("GG") }
        createButtonRow("Copy: Good luck!") { copyToClipboard("Good luck!") }
        createButtonRow("Copy: Rushing!") { copyToClipboard("Rushing!") }
        createButtonRow("Copy: Defending!") { copyToClipboard("Defending!") }
        createButtonRow("Copy: Need help!") { copyToClipboard("Need help!") }
        createButtonRow("STOP OVERLAY") { stopSelf() }
        createRow("Info", "Copies text to clipboard")
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Rich Bedrock", text))
        Toast.makeText(this, "Copied: $text", Toast.LENGTH_SHORT).show()
    }

    private fun toggleCrosshair(show: Boolean) {
        if (show) {
            if (crosshairView != null) return
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            crosshairView = TextView(this).apply {
                text = when (crosshairStyle) {
                    0 -> "+"
                    1 -> "×"
                    else -> "•"
                }
                setTextColor(Color.parseColor(crosshairColor))
                textSize = 34f
                setTypeface(typeface, Typeface.BOLD)
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            try {
                windowManager.addView(crosshairView, params)
                isCrosshairVisible = true
            } catch (_: Exception) {
                crosshairView = null
                isCrosshairVisible = false
                Toast.makeText(this, "Не удалось показать прицел", Toast.LENGTH_SHORT).show()
            }
        } else {
            removeOverlayView(crosshairView)
            crosshairView = null
            isCrosshairVisible = false
        }
    }

    private fun toggleDimmer(show: Boolean, colorHex: String) {
        if (show) {
            if (dimmerView != null) return
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            dimmerView = View(this).apply {
                setBackgroundColor(Color.parseColor(colorHex))
            }
            try {
                windowManager.addView(dimmerView, params)
                isDimmerVisible = true
            } catch (_: Exception) {
                dimmerView = null
                isDimmerVisible = false
            }
        } else {
            removeOverlayView(dimmerView)
            dimmerView = null
            isDimmerVisible = false
        }
    }

    private fun toggleNightMode(show: Boolean) {
        if (show) {
            if (nightModeView != null) return
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            nightModeView = View(this).apply {
                setBackgroundColor(Color.argb(35, 20, 45, 90))
            }
            try {
                windowManager.addView(nightModeView, params)
                isNightModeVisible = true
            } catch (_: Exception) {
                nightModeView = null
                isNightModeVisible = false
            }
        } else {
            removeOverlayView(nightModeView)
            nightModeView = null
            isNightModeVisible = false
        }
    }

    private fun toggleTimer(show: Boolean, seconds: Int, label: String) {
        timerCountdown?.cancel()
        timerCountdown = null
        removeOverlayView(timerView)
        timerView = null

        if (!show || seconds <= 0) return

        timerLabel = label
        timerEndAt = System.currentTimeMillis() + seconds * 1000L

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(38)
        }

        timerView = TextView(this).apply {
            text = "$timerLabel: ${formatTime(seconds)}"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded("#DD101010", "#3DDC84", 1, 14)
        }

        try {
            windowManager.addView(timerView, params)
        } catch (_: Exception) {
            timerView = null
            return
        }

        timerCountdown = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                timerView?.text = "$timerLabel: ${formatTime((millisUntilFinished / 1000L).toInt())}"
            }

            override fun onFinish() {
                timerView?.text = "$timerLabel: DONE"
                Toast.makeText(this@OverlayService, "$timerLabel завершён", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun toggleItemCounter(show: Boolean) {
        if (show) {
            if (floatingCounterView != null) return
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = dp(90)
            }
            floatingCounterView = TextView(this).apply {
                text = "Items: $itemCount"
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded("#DD101010", "#3DDC84", 1, 12)
            }
            try {
                windowManager.addView(floatingCounterView, params)
                isCounterVisible = true
            } catch (_: Exception) {
                floatingCounterView = null
                isCounterVisible = false
            }
        } else {
            removeOverlayView(floatingCounterView)
            floatingCounterView = null
            isCounterVisible = false
        }
    }

    private fun updateFloatingCounter() {
        floatingCounterView?.text = "Items: $itemCount"
    }

    private fun removeOverlayView(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        fpsCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        fpsCallback = null
        timerCountdown?.cancel()
        timerCountdown = null

        removeOverlayView(crosshairView)
        removeOverlayView(dimmerView)
        removeOverlayView(nightModeView)
        removeOverlayView(timerView)
        removeOverlayView(floatingCounterView)
        crosshairView = null
        dimmerView = null
        nightModeView = null
        timerView = null
        floatingCounterView = null

        if (::rootOverlay.isInitialized) removeOverlayView(rootOverlay)
        super.onDestroy()
    }
}
