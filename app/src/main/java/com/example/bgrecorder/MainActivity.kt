package com.example.bgrecorder

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private val worker = Executors.newSingleThreadExecutor()
    private var busy = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                sendToService(RecordingService.ACTION_START)
            } else {
                status.text = "Camera, microphone and notification permissions are required."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val version = TextView(this).apply {
            text = "Version ${Updater.installedVersionName(this@MainActivity)}"
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        val startBtn = Button(this).apply {
            text = "Start recording"
            setOnClickListener { startRecordingWithPermissions() }
        }
        val stopBtn = Button(this).apply {
            text = "Stop recording"
            setOnClickListener {
                sendToService(RecordingService.ACTION_STOP)
                status.text = "Stopping..."
            }
        }
        val batteryBtn = Button(this).apply {
            text = "Battery settings (allow background)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val updateBtn = Button(this).apply {
            text = "Check for updates"
            setOnClickListener { checkForUpdates(manual = true) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(version)
            addView(status)
            addView(startBtn)
            addView(stopBtn)
            addView(batteryBtn)
            addView(updateBtn)
        })

        // Quietly look for a new version every time the app opens.
        checkForUpdates(manual = false)
    }

    override fun onResume() {
        super.onResume()
        if (!busy) status.text = if (RecordingService.isRunning) "Recording..." else "Ready"
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
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
            status.text = "Recording... you can leave the app or turn the screen off."
        } else {
            startService(intent)
        }
    }

    // ---------- Updates ----------

    private fun checkForUpdates(manual: Boolean) {
        if (busy) return
        busy = true
        if (manual) status.text = "Checking for updates..."

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
                    error != null -> if (manual) status.text = "Update check failed: $error"
                    latest == null -> if (manual) status.text = "No published version found yet."
                    latest.versionCode <= Updater.installedVersionCode(this) ->
                        if (manual) status.text = "You already have the latest version."
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
            status.text = "Stop recording before updating."
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            status.text = "Allow BG Recorder to install apps, then press Check for updates again."
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }

        busy = true
        status.text = "Downloading update... 0%"
        worker.execute {
            try {
                val file = Updater.download(this, release.apkUrl) { percent ->
                    runOnUiThread { status.text = "Downloading update... $percent%" }
                }
                runOnUiThread {
                    busy = false
                    status.text = "Opening installer..."
                    Updater.install(this, file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = "Download failed: ${e.message}"
                }
            }
        }
    }
}
