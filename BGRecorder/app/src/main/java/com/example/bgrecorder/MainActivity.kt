package com.example.bgrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

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

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(startBtn)
            addView(stopBtn)
            addView(batteryBtn)
        })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (RecordingService.isRunning) "Recording..." else "Ready"
    }

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
}
