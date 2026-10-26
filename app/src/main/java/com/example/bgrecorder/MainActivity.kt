package com.example.bgrecorder

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var appTheme: AppTheme
    private var statusView: TextView? = null
    private var recordBtn: ImageButton? = null
    private var switchBtn: ImageButton? = null

    private var notice: String? = null          // temporary message (updates, permissions)
    private var shownRecording: Boolean? = null // what the record button currently shows
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    private val ticker = object : Runnable {
        override fun run() {
            refreshState()
            ui.postDelayed(this, 500)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                afterPermissions()
            } else {
                setNotice("Camera, microphone and notification permissions are required.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appTheme = Themes.load(this)
        buildUi()
        checkForUpdates() // quietly looks for a new version every time the app opens
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun circleButton(sizeDp: Int, iconRes: Int, description: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(iconRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = description
            setPadding(dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4), dp(sizeDp / 4))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                .apply { setMargins(dp(14), 0, dp(14), 0) }
            setOnClickListener { onClick() }
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

        // Tap the name to change the theme.
        val title = TextView(this).apply {
            text = "BG Recorder"
            textSize = 26f
            typeface = Typeface.create("serif", Typeface.BOLD)
            setTextColor(t.text)
            gravity = Gravity.CENTER
            setOnClickListener {
                appTheme = Themes.next(appTheme)
                Themes.save(this@MainActivity, appTheme)
                buildUi()
            }
        }
        val version = TextView(this).apply {
            text = "v${Updater.installedVersionName(this@MainActivity)}"
            textSize = 12f
            setTextColor(t.text)
            alpha = 0.55f
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            textSize = 16f
            setTextColor(t.text)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(10))
        }
        statusView = status

        val spacer = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(64), dp(64)) .apply { setMargins(dp(14), 0, dp(14), 0) } }
        val record = circleButton(104, R.drawable.ic_record, "Start or stop recording") { onRecordTapped() }
        val flip = circleButton(64, R.drawable.ic_flip_camera, "Switch camera") { onSwitchTapped() }
        recordBtn = record
        switchBtn = flip

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(spacer)
            addView(record)
            addView(flip)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(version)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(t.background)
            setPadding(dp(24), dp(48), dp(24), dp(48))
            addView(top, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, dp(16), 0, dp(40)) })
        })

        shownRecording = null
        refreshState()
    }

    private fun setNotice(text: String?) {
        notice = text
        refreshState()
    }

    private fun formatTime(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** Keeps buttons and text in sync with the real recording state. */
    private fun refreshState() {
        val t = appTheme
        val recording = RecordingService.isRunning
        val facing = if (Prefs.cameraFacing(this) == "front") "Front camera" else "Rear camera"

        if (shownRecording != recording) {
            shownRecording = recording
            recordBtn?.apply {
                setImageResource(if (recording) R.drawable.ic_stop else R.drawable.ic_record)
                setColorFilter(if (recording) Color.WHITE else t.onAccent)
                background = oval(if (recording) Color.parseColor("#D64545") else t.accent)
            }
            switchBtn?.apply {
                setColorFilter(t.text)
                background = oval(t.card)
                isEnabled = !recording
                alpha = if (recording) 0.35f else 1f
            }
        }

        statusView?.text = notice ?: if (recording) {
            val start = RecordingService.startTime
            if (start > 0) {
                "Recording  ${formatTime((SystemClock.elapsedRealtime() - start) / 1000)}  ·  $facing"
            } else {
                "Starting..."
            }
        } else {
            "$facing  ·  Ready"
        }
    }

    // ---------- Buttons ----------

    private fun onSwitchTapped() {
        if (RecordingService.isRunning) return
        notice = null
        val next = if (Prefs.cameraFacing(this) == "front") "back" else "front"
        Prefs.setCameraFacing(this, next)
        refreshState()
    }

    private fun onRecordTapped() {
        notice = null
        if (RecordingService.isRunning) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            refreshState()
        } else {
            requestPermissionsThenStart()
        }
    }

    private fun requestPermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) afterPermissions() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun afterPermissions() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val restricted = !pm.isIgnoringBatteryOptimizations(packageName)
        if (restricted && !Prefs.batteryAsked(this)) {
            // One-time tip: without this, some phones stop background recording.
            Prefs.setBatteryAsked(this)
            AlertDialog.Builder(this)
                .setTitle("Keep recording in the background")
                .setMessage("Set BG Recorder to \"Unrestricted\" (or \"Don't optimize\") so your phone doesn't stop the recording when the screen is off.")
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                .setNegativeButton("Skip") { _, _ -> startRecording() }
                .show()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        refreshState()
    }

    // ---------- Updates ----------

    private fun checkForUpdates() {
        if (busy) return
        busy = true

        worker.execute {
            var release: Updater.Release? = null
            try {
                release = Updater.fetchLatest()
            } catch (_: Exception) {
                // No internet or GitHub unreachable: stay quiet, try again next time.
            }

            runOnUiThread {
                busy = false
                val latest = release
                if (latest != null && latest.versionCode > Updater.installedVersionCode(this)) {
                    showUpdateDialog(latest)
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
            setNotice("Stop recording before updating.")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            setNotice("Allow BG Recorder to install apps, then reopen the app to update.")
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        busy = true
        setNotice("Downloading update... 0%")
        worker.execute {
            try {
                val file = Updater.download(this, release.apkUrl) { percent ->
                    runOnUiThread { setNotice("Downloading update... $percent%") }
                }
                runOnUiThread {
                    busy = false
                    setNotice(null)
                    Updater.install(this, file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    setNotice("Download failed: ${e.message}")
                }
            }
        }
    }
}
