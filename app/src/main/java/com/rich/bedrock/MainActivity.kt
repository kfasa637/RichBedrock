package com.rich.bedrock

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : ComponentActivity() {
    private val mc = "com.mojang.minecraftpe"
    private val bg = Color.parseColor("#0A0A0A")
    private val acc = Color.parseColor("#3DDC84")
    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { updateLaunchButton() }
    private lateinit var launchButton: MaterialButton

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setPadding(60, 0, 60, 0)
        }
        root.addView(TextView(this).apply {
            text = "Rich Bedrock"
            textSize = 42f
            setTextColor(acc)
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(20f, 0f, 0f, acc)
        })
        root.addView(TextView(this).apply {
            text = "Твой персональный буст"
            textSize = 16f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 20, 0, 120)
        })
        launchButton = MaterialButton(this).apply {
            isAllCaps = false
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            cornerRadius = 60
            setPadding(60, 60, 60, 60)
            elevation = 20f
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, OverlayService::class.java))
                    val i = packageManager.getLaunchIntentForPackage(mc)
                    if (i != null) startActivity(i) else Toast.makeText(this@MainActivity, "Minecraft не найден.", Toast.LENGTH_LONG).show()
                } else {
                    overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }
        updateLaunchButton()
        root.addView(launchButton)
        setContentView(root)
    }

    private fun updateLaunchButton() {
        if (Settings.canDrawOverlays(this)) {
            launchButton.text = "🚀 ЗАПУСТИТЬ MINECRAFT"
            launchButton.setBackgroundColor(acc)
            launchButton.setTextColor(Color.BLACK)
        } else {
            launchButton.text = "ДАЙ РАЗРЕШЕНИЕ НА ОВЕРЛЕЙ"
            launchButton.setBackgroundColor(Color.parseColor("#333333"))
            launchButton.setTextColor(Color.WHITE)
        }
    }
}
