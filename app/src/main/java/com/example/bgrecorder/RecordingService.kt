package com.example.bgrecorder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Foreground service that records video with Camera2 + MediaRecorder.
 * It keeps running when the app is closed or the screen is off.
 */
class RecordingService : Service() {

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var outputUri: Uri? = null
    private var pfd: ParcelFileDescriptor? = null
    private var recording = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    // ---------- Start ----------

    @SuppressLint("MissingPermission") // permissions are requested in MainActivity
    private fun startRecording() {
        if (isRunning) return
        isRunning = true

        // 1) Become a foreground service right away (required within a few seconds).
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }

        // 2) Keep the CPU awake when the screen is off.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bgrecorder:recording")
            .apply { acquire(4 * 60 * 60 * 1000L) } // safety timeout: 4 hours

        // 3) Background thread for camera callbacks.
        thread = HandlerThread("camera-thread").also { it.start() }
        handler = Handler(thread!!.looper)

        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val wanted = if (Prefs.cameraFacing(this) == "front")
                CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
            val cameraId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: cm.cameraIdList.first()
            val characteristics = cm.getCameraCharacteristics(cameraId)
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            prepareRecorder(sensorOrientation, pickVideoSize(characteristics))

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    stopOnMain()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error $error")
                    camera.close()
                    stopOnMain()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            stopOnMain()
        }
    }

    /** Biggest 16:9 size up to 1080p that this camera supports for video. */
    private fun pickVideoSize(characteristics: CameraCharacteristics): Size {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: return Size(1920, 1080)
        val allowed = sizes.filter { it.width <= 1920 && it.height <= 1080 }
        return allowed.filter { it.width * 9 == it.height * 16 }.maxByOrNull { it.width * it.height }
            ?: allowed.maxByOrNull { it.width * it.height }
            ?: Size(1280, 720)
    }

    private fun prepareRecorder(sensorOrientation: Int, size: Size) {
        // Create the output file in Movies/BGRecorder (visible in the gallery).
        val name = "REC_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BGRecorder")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        pfd = contentResolver.openFileDescriptor(outputUri!!, "w")

        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setOutputFile(pfd!!.fileDescriptor)
        mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        mr.setVideoSize(size.width, size.height)
        mr.setVideoFrameRate(30)
        mr.setVideoEncodingBitRate(10_000_000)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioEncodingBitRate(128_000)
        mr.setAudioSamplingRate(44_100)
        mr.setOrientationHint(sensorOrientation)
        mr.prepare()
        recorder = mr
    }

    private fun createSession() {
        val camera = cameraDevice ?: return
        val surface = recorder?.surface ?: return

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(surface)
        }.build()

        val executor = Executor { handler?.post(it) }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(OutputConfiguration(surface)),
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    try {
                        s.setRepeatingRequest(request, null, handler)
                        recorder?.start()
                        recording = true
                        startTime = SystemClock.elapsedRealtime()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to begin recording", e)
                        stopOnMain()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Session configuration failed")
                    stopOnMain()
                }
            }
        )
        camera.createCaptureSession(config)
    }

    // ---------- Stop ----------

    private fun stopOnMain() = mainHandler.post { stopRecording() }

    private fun stopRecording() {
        if (!isRunning) return
        isRunning = false
        startTime = 0L

        try { session?.stopRepeating() } catch (_: Exception) {}
        try { if (recording) recorder?.stop() } catch (e: RuntimeException) {
            Log.w(TAG, "Recorder stop failed (file may be empty)", e)
        }
        recording = false
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null

        // Make the finished video visible in the gallery.
        outputUri?.let {
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            try { contentResolver.update(it, done, null, null) } catch (_: Exception) {}
        }
        outputUri = null

        thread?.quitSafely()
        thread = null
        handler = null
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------- Notification ----------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stopPi
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording video")
            .setContentText("Tap Stop to finish and save")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.bgrecorder.START"
        const val ACTION_STOP = "com.example.bgrecorder.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIF_ID = 1
        private const val TAG = "RecordingService"

        @Volatile
        var isRunning = false

        /** Moment the recording really began (0 while not recording). */
        @Volatile
        var startTime = 0L
    }
}
