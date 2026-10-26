package com.example.bgrecorder

import android.content.Context
import android.graphics.Color

/** One colour palette for the whole app. */
data class AppTheme(
    val id: String,
    val label: String,
    val background: Int,
    val card: Int,
    val text: Int,
    val accent: Int,
    val onAccent: Int,
    val isDark: Boolean
)

object Themes {

    val all = listOf(
        AppTheme(
            id = "cream", label = "Cozy Cream",
            background = Color.parseColor("#FFF4E6"), card = Color.parseColor("#F3E2CC"),
            text = Color.parseColor("#4A3426"), accent = Color.parseColor("#C8794A"),
            onAccent = Color.WHITE, isDark = false
        ),
        AppTheme(
            id = "cocoa", label = "Warm Cocoa",
            background = Color.parseColor("#2B1D16"), card = Color.parseColor("#3C2A20"),
            text = Color.parseColor("#F3E3D3"), accent = Color.parseColor("#D99A6C"),
            onAccent = Color.parseColor("#2B1D16"), isDark = true
        ),
        AppTheme(
            id = "forest", label = "Forest Moss",
            background = Color.parseColor("#E8EFE3"), card = Color.parseColor("#D3E0CB"),
            text = Color.parseColor("#2F3E2C"), accent = Color.parseColor("#5F8A5A"),
            onAccent = Color.WHITE, isDark = false
        ),
        AppTheme(
            id = "dusk", label = "Dusk Lavender",
            background = Color.parseColor("#2A2438"), card = Color.parseColor("#3A3150"),
            text = Color.parseColor("#EDE6F7"), accent = Color.parseColor("#B79CE8"),
            onAccent = Color.parseColor("#2A2438"), isDark = true
        )
    )

    private const val PREFS = "settings"
    private const val KEY = "theme"

    fun load(ctx: Context): AppTheme {
        val id = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return all.firstOrNull { it.id == id } ?: all[0]
    }

    fun save(ctx: Context, theme: AppTheme) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.id).apply()
    }
}
