package com.rich.bedrock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private val mc = "com.mojang.minecraftpe"
    private val bg = Color.parseColor("#0F1412")
    private val card = Color.parseColor("#18201C")
    private val acc = Color.parseColor("#3DDC84")
    private val allowedExt = setOf(".mcpack", ".mcaddon", ".mcworld", ".mctemplate")
    private lateinit var body: LinearLayout

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(0, dp(28), 0, 0)
        }
        val tabs = LinearLayout(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        listOf<Pair<String, () -> Unit>>(
            "Главная" to { home() },
            "Контент" to { content() },
            "FPS" to { fps() }
        ).forEach { (n, f) -> tabs.addView(btn(n, true) { f() }, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(tabs)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        home()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    private fun tv(t: String, size: Float = 15f, c: Int = Color.WHITE) =
        TextView(this).apply { text = t; textSize = size; setTextColor(c); setLineSpacing(0f, 1.2f) }
    private fun btn(t: String, tab: Boolean = false, f: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setTextColor(if (tab) Color.WHITE else Color.BLACK)
        background = GradientDrawable().apply {
            setColor(if (tab) card else acc)
            cornerRadius = dp(12).toFloat()
        }
        setOnClickListener { f() }
    }
    private fun put(v: View) = body.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    private fun page(t: String) { body.removeAllViews(); put(tv(t, 24f, acc)) }

    private fun launchMc() {
        val i = packageManager.getLaunchIntentForPackage(mc)
        if (i != null) startActivity(i)
        else toast("Minecraft Bedrock не найден. Установи его из Google Play.")
    }

    private fun home() {
        page("Rich Bedrock")
        val ok = packageManager.getLaunchIntentForPackage(mc) != null
        put(tv(if (ok) "Minecraft Bedrock установлен" else "Minecraft Bedrock не найден."))
        put(btn("Запустить Minecraft") { launchMc() })
    }

    private fun content() {
        page("Контент")
        put(tv("Выбери файл .mcpack, .mcaddon, .mcworld или .mctemplate — откроем в Minecraft."))
        put(btn("Выбрать файл") { pickFile.launch(arrayOf("*/*")) })
    }

    private fun importFile(u: Uri) {
        var name = "import.mcpack"
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i)
            }
        }
        val l = name.lowercase()
        if (allowedExt.none { l.endsWith(it) }) {
            toast("Нужен .mcpack, .mcaddon, .mcworld или .mctemplate")
            return
        }
        val dir = File(cacheDir, "imports").apply { mkdirs() }
        val f = File(dir, name.replace("/", "_"))
        try {
            contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) { toast("Не удалось прочитать файл"); return }
        val out = FileProvider.getUriForFile(this, "$packageName.files", f)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(out, "application/octet-stream")
            .setPackage(mc)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(intent) }
        catch (e: ActivityNotFoundException) { toast("Не удалось открыть в Minecraft") }
    }

    private fun fps() {
        page("Буст FPS")
        put(tv("Реальный прирост даёт снижение нагрузки на GPU и защита от перегрева. Профили (Minecraft → Настройки → Видео):"))
        listOf(
            "Максимум FPS" to "Дальность 6. Красивая графика выкл. Листья выкл. Частицы минимум.",
            "Баланс" to "Дальность 8–10. Красивая графика выкл. Только лёгкие паки.",
            "Качество" to "Дальность 12. Красивая графика вкл. Ждите просадок."
        ).forEach { (n, d) -> put(tv(n, 17f, acc)); put(tv(d)) }
        put(btn("Настройки энергопитания") { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) })
        put(btn("Запустить Minecraft") { launchMc() })
    }
}
