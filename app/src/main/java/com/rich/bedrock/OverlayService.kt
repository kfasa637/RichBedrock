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

    companion object {
        private const val GREEN = "#3DDC84"
        private const val DARK = "#101010"
        private const val PANEL = "#1A1A1A"
        private const val BUTTON = "#292929"
        private const val CHANNEL_ID = "rich_bedrock_overlay"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private lateinit var rootOverlay: FrameLayout
    private lateinit var fab: TextView
    private lateinit var mainMenu: LinearLayout
    private lateinit var contentContainer: LinearLayout

    private var fpsText: TextView? = null
    private var pingText: TextView? = null
    private var ramText: TextView? = null
    private var cpsText: TextView? = null
    private var stopwatchText: TextView? = null
    private var comboText: TextView? = null
    private var hitText: TextView? = null
    private var resourceText: TextView? = null
    private var itemCounterView: TextView? = null

    private var crosshairLayer: FrameLayout? = null
    private var dimmerLayer: View? = null
    private var nightLayer: View? = null
    private var timerLayer: TextView? = null
    private var floatingCounterView: TextView? = null

    private var overlayParams: WindowManager.LayoutParams? = null
    private var crosshairParams: WindowManager.LayoutParams? = null
    private var dimmerParams: WindowManager.LayoutParams? = null
    private var nightParams: WindowManager.LayoutParams? = null
    private var timerParams: WindowManager.LayoutParams? = null
    private var counterParams: WindowManager.LayoutParams? = null

    private var isMenuOpen = false
    private var isCrosshairVisible = false
    private var isDimmerVisible = false
    private var isNightModeVisible = false
    private var isStopwatchRunning = false
    private var isCounterVisible = false
    private var bedDestroyed = false
    private var isReactionRunning = false

    private var crosshairStyle = 0
    private var crosshairColor = GREEN
    private var dimmerAlpha = 0x55
    private var nightAlpha = 0x35

    private var cpsCount = 0
    private var comboCount = 0
    private var hitCount = 0
    private var itemCount = 0
    private var stopwatchSeconds = 0
    private var reactionStartedAt = 0L
    private var reactionRunnable: Runnable? = null
    private var lastFrameTime = 0L
    private var frames = 0
    private var pingInProgress = false

    private var fpsCallback: Choreographer.FrameCallback? = null
    private var timerCountdown: CountDownTimer? = null
    private var timerEndAt = 0L
    private var timerLabel = "Таймер"
    private var selectedPreset = "Обычный"

    private val resourceCounts = linkedMapOf(
        "Железо" to 0,
        "Золото" to 0,
        "Алмазы" to 0,
        "Изумруды" to 0
    )

    private val quickMessages = listOf(
        "Нужна помощь!",
        "Защищайте кровать!",
        "Иду на мид",
        "Нужны ресурсы",
        "Отступаем"
    )

    private val handler = Handler(Looper.getMainLooper())

    private val statsUpdater = object : Runnable {
        override fun run() {
            updateRam()
            updatePing()
            if (isStopwatchRunning) {
                stopwatchSeconds++
                stopwatchText?.text = formatTime(stopwatchSeconds)
            }
            cpsText?.text = cpsCount.toString()
            cpsCount = 0
            handler.postDelayed(this, 1000L)
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Rich Bedrock overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Rich Bedrock работает")
            .setContentText("Игровая панель поверх Minecraft")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateRam() {
        try {
            val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            val usedMb = (info.totalMem - info.availMem) / (1024L * 1024L)
            ramText?.text = "$usedMb MB"
        } catch (_: Exception) {
            ramText?.text = "—"
        }
    }

    private fun updatePing() {
        if (pingInProgress) return
        pingInProgress = true
        thread(name = "RichBedrockPing") {
            val start = System.currentTimeMillis()
            val reachable = try {
                InetAddress.getByName("1.1.1.1").isReachable(700)
            } catch (_: Exception) {
                false
            }
            val elapsed = System.currentTimeMillis() - start
            handler.post {
                pingText?.text = if (reachable) "$elapsed ms*" else "Недоступно"
                pingInProgress = false
            }
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
                        fpsText?.text = "~$fps"
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
        val params = WindowManager.LayoutParams(
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
        overlayParams = params

        rootOverlay = FrameLayout(this)

        fab = TextView(this).apply {
            text = "R"
            setTextColor(Color.BLACK)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 26f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#00FF87"), Color.parseColor("#00A854"))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.WHITE)
            }
            setOnClickListener {
                isMenuOpen = true
                fab.visibility = View.GONE
                mainMenu.visibility = View.VISIBLE
            }
        }

        mainMenu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#F0101010", GREEN, 2, 22)
            visibility = View.GONE
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(14), dp(10))
            background = rounded("#202020", "#202020", 0, 18)
            gravity = Gravity.CENTER_VERTICAL
        }

        titleBar.addView(TextView(this).apply {
            text = "RICH BEDROCK 2.0"
            setTextColor(Color.parseColor(GREEN))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        titleBar.addView(TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener {
                isMenuOpen = false
                mainMenu.visibility = View.GONE
                fab.visibility = View.VISIBLE
            }
        })

        titleBar.addView(TextView(this).apply {
            text = "×"
            setTextColor(Color.WHITE)
            textSize = 23f
            setPadding(dp(4), 0, dp(4), 0)
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
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        try {
                            windowManager.updateViewLayout(rootOverlay, params)
                        } catch (_: Exception) {
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> return true
                }
                return false
            }
        })
        mainMenu.addView(titleBar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(350), dp(440))
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(88), -1)
            setPadding(0, 0, dp(8), 0)
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
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
        windowManager.addView(rootOverlay, params)

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

    private fun passiveWindowFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
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
        textSize = 12f
        setPadding(dp(8), dp(13), dp(4), dp(13))
        background = rounded("#00000000")
    }

    private fun highlightTab(selected: TextView, others: List<TextView>) {
        selected.setTextColor(Color.parseColor(GREEN))
        selected.background = rounded("#2A2A2A")
        others.forEach {
            it.setTextColor(Color.LTGRAY)
            it.background = rounded("#00000000")
        }
    }

    private fun createSection(title: String, subtitle: String = "") {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
                topMargin = dp(3)
            }
            background = rounded("#24352B", radiusDp = 10)
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor(GREEN))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (subtitle.isNotEmpty()) {
            row.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Color.LTGRAY)
                textSize = 10f
            })
        }
        contentContainer.addView(row)
    }

    private fun createRow(name: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(11), dp(11), dp(11), dp(11))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            }
            background = rounded(PANEL, radiusDp = 11)
        }
        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val valueText = TextView(this).apply {
            text = value
            setTextColor(Color.parseColor(GREEN))
            textSize = 12f
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
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(12), dp(9), dp(12))
            background = rounded(BUTTON, radiusDp = 10)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
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
            setPadding(dp(11), dp(9), dp(10), dp(9))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(7)
            }
            background = rounded(PANEL, radiusDp = 11)
        }
        row.addView(TextView(this).apply {
            text = name
            setTextColor(Color.WHITE)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        var enabled = initialState
        val toggle = TextView(this).apply {
            text = if (enabled) "ON" else "OFF"
            setTextColor(if (enabled) Color.parseColor(GREEN) else Color.GRAY)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(if (enabled) "#1A3A25" else "#333333", radiusDp = 18)
            setOnClickListener {
                enabled = !enabled
                text = if (enabled) "ON" else "OFF"
                setTextColor(if (enabled) Color.parseColor(GREEN) else Color.GRAY)
                background = rounded(
                    if (enabled) "#1A3A25" else "#333333",
                    radiusDp = 18
                )
                onChanged(enabled)
            }
        }
        row.addView(toggle)
        contentContainer.addView(row)
    }

    private fun showHudTab() {
        contentContainer.removeAllViews()
        createSection("МОНИТОРИНГ", "приблизительно")
        fpsText = createRow("FPS панели", "—")
        pingText = createRow("Проверка сети", "—")
        ramText = createRow("Занято системной RAM", "—")
        createRow("Подсказка", "Показания не из игры")

        createSection("МАТЧ")
        stopwatchText = createRow("Секундомер", formatTime(stopwatchSeconds))
        createToggleRow("Запустить секундомер", isStopwatchRunning) {
            isStopwatchRunning = it
        }
        createButtonRow("Сбросить секундомер") {
            stopwatchSeconds = 0
            stopwatchText?.text = "00:00"
        }

        createSection("СЧЁТЧИК КЛИКОВ")
        cpsText = createRow("CPS (ручной)", "0")
        createButtonRow("Засчитать клик") {
            cpsCount++
            cpsText?.text = cpsCount.toString()
        }
        createButtonRow("Сбросить CPS") {
            cpsCount = 0
            cpsText?.text = "0"
        }
    }

    private fun showPvpTab() {
        contentContainer.removeAllViews()

        createSection("ПРИЦЕЛ")
        createToggleRow("Показывать прицел", isCrosshairVisible) {
            toggleCrosshair(it)
        }
        createButtonRow("Стиль прицела: + / × / •") {
            crosshairStyle = (crosshairStyle + 1) % 3
            refreshCrosshair()
            Toast.makeText(this, "Стиль: ${crosshairSymbol()}", Toast.LENGTH_SHORT).show()
        }
        createButtonRow("Цвет прицела") {
            crosshairColor = when (crosshairColor) {
                GREEN -> "#FF5555"
                "#FF5555" -> "#FFFFFF"
                else -> GREEN
            }
            refreshCrosshair()
        }

        createSection("РУЧНАЯ СТАТИСТИКА")
        comboText = createRow("Combo", comboCount.toString())
        createButtonRow("+1 к Combo") {
            comboCount++
            comboText?.text = comboCount.toString()
        }
        createButtonRow("Сбросить Combo") {
            comboCount = 0
            comboText?.text = "0"
        }

        hitText = createRow("Попадания (ручной счёт)", hitCount.toString())
        createButtonRow("+1 попадание") {
            hitCount++
            hitText?.text = hitCount.toString()
        }
        createButtonRow("Сбросить попадания") {
            hitCount = 0
            hitText?.text = "0"
        }

        createSection("ЭКРАН")
        createToggleRow("Затемнение по краям / фокус", isDimmerVisible) {
            toggleDimmer(it)
        }
        createToggleRow("Синий фильтр", isNightModeVisible) {
            toggleNightMode(it)
        }

        createSection("ТРЕНИРОВКА РЕАКЦИИ")
        createButtonRow("Начать тест реакции") {
            startReactionTest()
        }
        createRow("Результат", "Нажми после зелёного сигнала")
        createButtonRow("Сбросить статистику PvP") {
            comboCount = 0
            hitCount = 0
            comboText?.text = "0"
            hitText?.text = "0"
        }

        createSection("ПРЕСЕТЫ")
        createButtonRow("Пресет: Компактный") { applyPreset("Компактный") }
        createButtonRow("Пресет: Яркий") { applyPreset("Яркий") }
        createButtonRow("Пресет: Обычный") { applyPreset("Обычный") }

        createRow("Важно", "Нет автоатаки и аимбота")
        createRow("Target HUD / Reach", "Нужны игровые данные")
    }

    private fun showBedwarsTab() {
        contentContainer.removeAllViews()

        createSection("ТАЙМЕРЫ ГЕНЕРАТОРОВ", "ручной запуск")
        createButtonRow("Алмазы — 15 секунд") { startTimer(15, "Алмазы") }
        createButtonRow("Изумруды — 30 секунд") { startTimer(30, "Изумруды") }
        createButtonRow("Таймер — 1 минута") { startTimer(60, "Генератор") }
        createButtonRow("Таймер — 3 минуты") { startTimer(180, "Генератор") }
        createButtonRow("Остановить таймер") { stopTimer() }

        createSection("РЕСУРСЫ")
        resourceText = createRow("Железо 0 | Золото 0", "Алмазы 0")
        createResourceButtons("Железо")
        createResourceButtons("Золото")
        createResourceButtons("Алмазы")
        createResourceButtons("Изумруды")
        createButtonRow("Сбросить ресурсы") {
            resourceCounts.keys.forEach { key -> resourceCounts[key] = 0 }
            updateResourceSummary()
        }

        createSection("КРОВАТЬ И ПРЕДМЕТЫ")
        createToggleRow("Кровать уничтожена", bedDestroyed) {
            bedDestroyed = it
            Toast.makeText(
                this,
                if (it) "Отметка: кровать уничтожена" else "Отметка снята",
                Toast.LENGTH_SHORT
            ).show()
        }
        createRow("Предметы (ручной счёт)", itemCount.toString())
        createButtonRow("+1 предмет") {
            itemCount++
            updateItemCounter()
        }
        createButtonRow("−1 предмет") {
            itemCount = (itemCount - 1).coerceAtLeast(0)
            updateItemCounter()
        }
        createButtonRow("Сбросить предметы") {
            itemCount = 0
            updateItemCounter()
        }
        createButtonRow("Показать плавающий счётчик") {
            toggleFloatingCounter(!isCounterVisible)
        }
    }

    private fun createResourceButtons(resource: String) {
        createButtonRow("$resource: +1") {
            resourceCounts[resource] = (resourceCounts[resource] ?: 0) + 1
            updateResourceSummary()
        }
        createButtonRow("$resource: −1") {
            resourceCounts[resource] = ((resourceCounts[resource] ?: 0) - 1).coerceAtLeast(0)
            updateResourceSummary()
        }
    }

    private fun updateResourceSummary() {
        val iron = resourceCounts["Железо"] ?: 0
        val gold = resourceCounts["Золото"] ?: 0
        val diamond = resourceCounts["Алмазы"] ?: 0
        val emerald = resourceCounts["Изумруды"] ?: 0
        resourceText?.text = "Ж:$iron З:$gold А:$diamond И:$emerald"
    }

    private fun updateItemCounter() {
        itemCounterView?.text = "Предметы: $itemCount"
        if (isCounterVisible) floatingCounterView?.text = "Предметы: $itemCount"
        refreshBedwarsTabIfVisible()
    }

    private fun refreshBedwarsTabIfVisible() {
        // The visible tab is rebuilt only when the user opens it again.
    }

    private fun showMacrosTab() {
        contentContainer.removeAllViews()

        createSection("БЫСТРЫЕ СООБЩЕНИЯ")
        createRow("Как использовать", "Копировать → вставить в чат")
        quickMessages.forEach { message ->
            createButtonRow("Копировать: $message") {
                copyToClipboard(message)
            }
        }

        createSection("РУЧНЫЕ ДЕЙСТВИЯ")
        createButtonRow("Скопировать координаты-шаблон") {
            copyToClipboard("X: ___  Y: ___  Z: ___")
        }
        createButtonRow("Скопировать команду /party") {
            copyToClipboard("/party ")
        }
        createButtonRow("Скопировать GG") {
            copyToClipboard("GG!")
        }

        createSection("ТРЕНИРОВОЧНЫЕ ИНСТРУМЕНТЫ")
        createButtonRow("Запустить 10-секундный таймер") {
            startTimer(10, "Тренировка")
        }
        createButtonRow("Сбросить ручные PvP-счётчики") {
            comboCount = 0
            hitCount = 0
            cpsCount = 0
            Toast.makeText(this, "Счётчики сброшены", Toast.LENGTH_SHORT).show()
        }

        createRow("Автоматизация", "Только копирование текста")
        createRow("Безопасность", "Нет автодействий в игре")
    }

    private fun copyToClipboard(value: String) {
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("RichBedrock", value))
            Toast.makeText(this, "Скопировано: $value", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось скопировать", Toast.LENGTH_SHORT).show()
        }
    }

    private fun crosshairSymbol(): String {
        return when (crosshairStyle) {
            0 -> "+"
            1 -> "×"
            else -> "•"
        }
    }

    private fun toggleCrosshair(enabled: Boolean) {
        isCrosshairVisible = enabled
        if (enabled) {
            if (crosshairLayer == null) {
                crosshairLayer = FrameLayout(this).apply {
                    addView(TextView(this@OverlayService).apply {
                        text = crosshairSymbol()
                        textSize = 30f
                        setTypeface(typeface, Typeface.BOLD)
                        gravity = Gravity.CENTER
                        setTextColor(Color.parseColor(crosshairColor))
                        layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                    })
                }
                crosshairParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayWindowType(),
                    passiveWindowFlags(),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                try {
                    windowManager.addView(crosshairLayer, crosshairParams)
                } catch (_: Exception) {
                    crosshairLayer = null
                    isCrosshairVisible = false
                    Toast.makeText(this, "Не удалось показать прицел", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            removeLayer(crosshairLayer)
            crosshairLayer = null
            crosshairParams = null
        }
    }

    private fun refreshCrosshair() {
        if (!isCrosshairVisible) return
        removeLayer(crosshairLayer)
        crosshairLayer = null
        toggleCrosshair(true)
    }

    private fun toggleDimmer(enabled: Boolean) {
        isDimmerVisible = enabled
        if (enabled) {
            if (dimmerLayer == null) {
                dimmerLayer = View(this).apply {
                    setBackgroundColor(Color.argb(dimmerAlpha, 0, 0, 0))
                }
                dimmerParams = fullScreenParams()
                try {
                    windowManager.addView(dimmerLayer, dimmerParams)
                } catch (_: Exception) {
                    dimmerLayer = null
                    isDimmerVisible = false
                }
            }
        } else {
            removeLayer(dimmerLayer)
            dimmerLayer = null
            dimmerParams = null
        }
    }

    private fun toggleNightMode(enabled: Boolean) {
        isNightModeVisible = enabled
        if (enabled) {
            if (nightLayer == null) {
                nightLayer = View(this).apply {
                    setBackgroundColor(Color.argb(nightAlpha, 35, 70, 255))
                }
                nightParams = fullScreenParams()
                try {
                    windowManager.addView(nightLayer, nightParams)
                } catch (_: Exception) {
                    nightLayer = null
                    isNightModeVisible = false
                }
            }
        } else {
            removeLayer(nightLayer)
            nightLayer = null
            nightParams = null
        }
    }

    private fun fullScreenParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            passiveWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun startTimer(seconds: Int, label: String) {
        timerCountdown?.cancel()
        timerLabel = label
        timerEndAt = System.currentTimeMillis() + seconds * 1000L
        showTimerLayer()
        timerCountdown = object : CountDownTimer(seconds * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                timerLayer?.text = "$timerLabel  ${formatMillis(millisUntilFinished)}"
            }

            override fun onFinish() {
                timerLayer?.text = "$timerLabel  ГОТОВО!"
                Toast.makeText(this@OverlayService, "$timerLabel: время!", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun showTimerLayer() {
        if (timerLayer == null) {
            timerLayer = TextView(this).apply {
                setTextColor(Color.BLACK)
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(GREEN, radiusDp = 18)
            }
            timerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                passiveWindowFlags(),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(35)
            }
            try {
                windowManager.addView(timerLayer, timerParams)
            } catch (_: Exception) {
                timerLayer = null
                timerParams = null
            }
        }
        timerLayer?.text = "$timerLabel  ${formatMillis((timerEndAt - System.currentTimeMillis()).coerceAtLeast(0L))}"
    }

    private fun stopTimer() {
        timerCountdown?.cancel()
        timerCountdown = null
        removeLayer(timerLayer)
        timerLayer = null
        timerParams = null
    }

    private fun toggleFloatingCounter(enabled: Boolean) {
        isCounterVisible = enabled
        if (enabled) {
            if (floatingCounterView == null) {
                floatingCounterView = TextView(this).apply {
                    text = "Предметы: $itemCount"
                    setTextColor(Color.BLACK)
                    textSize = 13f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(GREEN, radiusDp = 18)
                }
                counterParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayWindowType(),
                    passiveWindowFlags(),
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(14)
                    y = dp(90)
                }
                try {
                    windowManager.addView(floatingCounterView, counterParams)
                } catch (_: Exception) {
                    floatingCounterView = null
                    counterParams = null
                    isCounterVisible = false
                }
            }
        } else {
            removeLayer(floatingCounterView)
            floatingCounterView = null
            counterParams = null
        }
    }

    private fun startReactionTest() {
        if (isReactionRunning) return
        isReactionRunning = true
        val prompt = TextView(this).apply {
            text = "ЖДИ..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded("#AA222222", radiusDp = 14)
            setOnClickListener {
                if (!isReactionRunning) return@setOnClickListener
                val elapsed = System.currentTimeMillis() - reactionStartedAt
                isReactionRunning = false
                handler.removeCallbacks(reactionRunnable ?: Runnable {})
                Toast.makeText(
                    this@OverlayService,
                    "Реакция: ${elapsed} мс",
                    Toast.LENGTH_LONG
                ).show()
                removeLayer(this)
            }
        }

        val params = WindowManager.LayoutParams(
            dp(220),
            dp(100),
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(prompt, params)
        } catch (_: Exception) {
            isReactionRunning = false
            Toast.makeText(this, "Не удалось запустить тест", Toast.LENGTH_SHORT).show()
            return
        }

        val delay = (1500L..4000L).random()
        val runnable = Runnable {
            if (isReactionRunning) {
                prompt.text = "ЖМИ!"
                prompt.setTextColor(Color.BLACK)
                prompt.background = rounded(GREEN, radiusDp = 14)
                reactionStartedAt = System.currentTimeMillis()
            }
        }
        reactionRunnable = runnable
        handler.postDelayed(runnable, delay)

        // A tap before the green signal is a false start.
        prompt.setOnClickListener {
            if (reactionStartedAt == 0L) {
                isReactionRunning = false
                handler.removeCallbacks(runnable)
                Toast.makeText(this, "Фальстарт! Попробуй ещё раз", Toast.LENGTH_SHORT).show()
                removeLayer(prompt)
            } else {
                val elapsed = System.currentTimeMillis() - reactionStartedAt
                isReactionRunning = false
                reactionStartedAt = 0L
                Toast.makeText(this, "Реакция: ${elapsed} мс", Toast.LENGTH_LONG).show()
                removeLayer(prompt)
            }
        }
        reactionStartedAt = 0L
    }

    private fun applyPreset(name: String) {
        selectedPreset = name
        when (name) {
            "Компактный" -> {
                dimmerAlpha = 0x35
                nightAlpha = 0x25
            }
            "Яркий" -> {
                dimmerAlpha = 0x65
                nightAlpha = 0x45
            }
            else -> {
                dimmerAlpha = 0x55
                nightAlpha = 0x35
            }
        }
        if (isDimmerVisible) {
            toggleDimmer(false)
            toggleDimmer(true)
        }
        if (isNightModeVisible) {
            toggleNightMode(false)
            toggleNightMode(true)
        }
        Toast.makeText(this, "Пресет применён: $selectedPreset", Toast.LENGTH_SHORT).show()
    }

    private fun removeLayer(view: View?) {
        if (view == null) return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun formatTime(seconds: Int): String {
        val minutes = seconds / 60
        val remaining = seconds % 60
        return String.format("%02d:%02d", minutes, remaining)
    }

    private fun formatMillis(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        return String.format("%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsUpdater)
        reactionRunnable?.let { handler.removeCallbacks(it) }
        fpsCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        timerCountdown?.cancel()
        timerCountdown = null

        removeLayer(crosshairLayer)
        removeLayer(dimmerLayer)
        removeLayer(nightLayer)
        removeLayer(timerLayer)
        removeLayer(floatingCounterView)
        crosshairLayer = null
        dimmerLayer = null
        nightLayer = null
        timerLayer = null
        floatingCounterView = null

        try {
            if (::rootOverlay.isInitialized) windowManager.removeView(rootOverlay)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}
