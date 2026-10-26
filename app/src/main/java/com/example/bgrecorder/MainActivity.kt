package com.example.bgrecorder

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private var status: TextView? = null
    private var statusText = "Ready"
    private lateinit var appTheme: AppTheme
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                sendToService(RecordingService.ACTION_START)
            } else {
                setStatus("Camera, microphone and notification permissions are required.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appTheme = Themes.load(this)
        buildUi()

        // Quietly look for a new version every time the app opens.
        checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (!busy) setStatus(if (RecordingService.isRunning) "Recording..." else "Ready")
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun setStatus(text: String) {
        statusText = text
        status?.text = text
    }

    private fun roundedBackground(fill: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 2) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    private fun styledButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(textColor)
            background = roundedBackground(fill, 24)
            stateListAnimator = null
            minHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { setMargins(0, dp(6), 0, dp(6)) }
            setOnClickListener { onClick() }
        }

    private fun themeChip(option: AppTheme): Button {
        val selected = option.id == appTheme.id
        return Button(this).apply {
            text = (if (selected) "✓ " else "") + option.label
            isAllCaps = false
            textSize = 14f
            setTextColor(option.text)
            background = roundedBackground(option.background, 20, option.accent, if (selected) 3 else 1)
            stateListAnimator = null
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
            setOnClickListener {
                Themes.save(this@MainActivity, option)
                appTheme = option
                buildUi()
            }
        }
    }

    private fun buildUi() {
        val t = appTheme

        @Suppress("DEPRECATION")
        window.statusBarColor = t.background
        @Suppress("DEPRECATION")
        window.navigationBarColor = t.background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !t.isDark
            isAppearanceLightNavigationBars = !t.isDark
        }

        val title = TextView(this).apply {
            text = "BG Recorder"
            textSize = 30f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(t.text)
            gravity = Gravity.CENTER
        }
        val version = TextView(this).apply {
            text = "Version ${Updater.installedVersionName(this@MainActivity)}  ·  ${t.label}"
            textSize = 13f
            setTextColor(t.text)
            alpha = 0.7f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        status = TextView(this).apply {
            text = statusText
            textSize = 17f
            setTextColor(t.text)
            gravity = Gravity.CENTER
            background = roundedBackground(t.card, 20)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .apply { setMargins(0, 0, 0, dp(12)) }
        }

        val themeLabel = TextView(this).apply {
            text = "Theme"
            textSize = 15f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(t.text)
            setPadding(dp(4), dp(20), 0, dp(4))
        }
        fun chipRow(a: AppTheme, b: AppTheme) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(themeChip(a))
            addView(themeChip(b))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
            addView(title)
            addView(version)
            addView(status)
            addView(styledButton("Start recording", t.accent, t.onAccent) {
                startRecordingWithPermissions()
            })
            addView(styledButton("Stop recording", t.card, t.text) {
                sendToService(RecordingService.ACTION_STOP)
                setStatus("Stopping...")
            })
            addView(styledButton("Battery settings (allow background)", t.card, t.text) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
            addView(styledButton("Check for updates", t.card, t.text) {
                checkForUpdates(manual = true)
            })
            addView(themeLabel)
            addView(chipRow(Themes.all[0], Themes.all[1]))
            addView(chipRow(Themes.all[2], Themes.all[3]))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(t.background)
            isFillViewport = true
            addView(column)
        })
    }

    // ---------- Recording ----------

    private fun startRecordingWithPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            sendToService(RecordingService.ACTION_START)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        if (action == RecordingService.ACTION_START) {
            ContextCompat.startForegroundService(this, intent)
            setStatus("Recording... you can leave the app or turn the screen off.")
        } else {
            startService(intent)
        }
    }

    // ---------- Updates ----------

    private fun checkForUpdates(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) setStatus("Checking for updates...")

        worker.execute {
            var release: Updater.Release? = null
            var error: String? = null
            try {
                release = Updater.fetchLatest()
            } catch (e: Exception) {
                error = e.message ?: "Network error"
            }

            runOnUiThread {
                busy = false
                val latest = release
                when {
                    error != null -> if (manual) setStatus("Update check failed: $error")
                    latest == null -> if (manual) setStatus("No published version found yet.")
                    latest.versionCode <= Updater.installedVersionCode(this) ->
                        if (manual) setStatus("You already have the latest version.")
                    else -> showUpdateDialog(latest)
                }
            }
        }
    }

    private fun showUpdateDialog(release: Updater.Release) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("${release.name}\n\n${release.notes}")
            .setPositiveButton("Update") { _, _ -> startUpdate(release) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun startUpdate(release: Updater.Release) {
        if (RecordingService.isRunning) {
            setStatus("Stop recording before updating.")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            setStatus("Allow BG Recorder to install apps, then press Check for updates again.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        busy = true
        setStatus("Downloading update... 0%")
        worker.execute {
            try {
                val file = Updater.download(this, release.apkUrl) { percent ->
                    runOnUiThread { setStatus("Downloading update... $percent%") }
                }
                runOnUiThread {
                    busy = false
                    setStatus("Opening installer...")
                    Updater.install(this, file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setStatus("Download failed: ${e.message}")
                }
            }
        }
    }
}
