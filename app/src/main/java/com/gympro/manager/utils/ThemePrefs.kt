package com.gympro.manager.utils

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemePrefs {

    private const val FILE = "theme_prefs"
    private const val KEY = "night_mode"

    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(c: Context): Int = prefs(c).getInt(KEY, SYSTEM)

    fun set(c: Context, mode: Int) {
        prefs(c).edit().putInt(KEY, mode).apply()
        apply(mode)
    }

    fun apply(c: Context) = apply(get(c))

    private fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }
}
