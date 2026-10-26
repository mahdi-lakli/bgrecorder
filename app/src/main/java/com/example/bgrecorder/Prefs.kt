package com.example.bgrecorder

import android.content.Context

/** Small settings stored on the phone. */
object Prefs {
    private const val FILE = "settings"
    private const val CAMERA = "camera_facing"
    private const val BATTERY_ASKED = "battery_asked"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "back" or "front" */
    fun cameraFacing(ctx: Context): String = prefs(ctx).getString(CAMERA, "back") ?: "back"

    fun setCameraFacing(ctx: Context, facing: String) {
        prefs(ctx).edit().putString(CAMERA, facing).apply()
    }

    fun batteryAsked(ctx: Context): Boolean = prefs(ctx).getBoolean(BATTERY_ASKED, false)

    fun setBatteryAsked(ctx: Context) {
        prefs(ctx).edit().putBoolean(BATTERY_ASKED, true).apply()
    }
}
