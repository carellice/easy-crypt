package com.easycrypt.keyboard

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Opzioni della tastiera, modificabili dall'app. */
class Prefs(context: Context) {
    val shared: SharedPreferences =
        context.applicationContext.getSharedPreferences("easycrypt_settings", Context.MODE_PRIVATE)

    /** Font scelto dall'utente per disegnare le emoji nel pannello della tastiera. */
    val emojiFont = File(context.applicationContext.filesDir, "emoji_font.ttf")

    var numberRow: Boolean
        get() = shared.getBoolean(NUMBER_ROW, false)
        set(value) = shared.edit().putBoolean(NUMBER_ROW, value).apply()

    var autocorrect: Boolean
        get() = shared.getBoolean(AUTOCORRECT, false)
        set(value) = shared.edit().putBoolean(AUTOCORRECT, value).apply()

    /** Vibrazione alla pressione dei tasti. */
    var vibration: Boolean
        get() = shared.getBoolean(VIBRATION, true)
        set(value) = shared.edit().putBoolean(VIBRATION, value).apply()

    /** Digitazione a scorrimento. */
    var glide: Boolean
        get() = shared.getBoolean(GLIDE, true)
        set(value) = shared.edit().putBoolean(GLIDE, value).apply()

    /** Distanza della tastiera dal bordo inferiore dello schermo, in dp. */
    var bottomOffset: Int
        get() = shared.getInt(BOTTOM_OFFSET, DEFAULT_BOTTOM_OFFSET)
        set(value) = shared.edit().putInt(BOTTOM_OFFSET, value).apply()

    /** Cambia a ogni sostituzione del font emoji, così la tastiera sa di doversi ridisegnare. */
    var emojiFontVersion: Int
        get() = shared.getInt(EMOJI_FONT_VERSION, 0)
        set(value) = shared.edit().putInt(EMOJI_FONT_VERSION, value).apply()

    /** Emoji usate di recente, dalla più recente. */
    var recentEmoji: List<String>
        get() = shared.getString(RECENT_EMOJI, "").orEmpty().split(' ').filter { it.isNotEmpty() }
        set(value) = shared.edit().putString(RECENT_EMOJI, value.joinToString(" ")).apply()

    /** L'introduzione del primo avvio è già stata mostrata. */
    var onboardingDone: Boolean
        get() = shared.getBoolean(ONBOARDING_DONE, false)
        set(value) = shared.edit().putBoolean(ONBOARDING_DONE, value).apply()

    companion object {
        private const val ONBOARDING_DONE = "onboarding_done"
        private const val RECENT_EMOJI = "recent_emoji"
        const val MAX_BOTTOM_OFFSET = 80
        private const val DEFAULT_BOTTOM_OFFSET = 12
        private const val NUMBER_ROW = "number_row"
        private const val AUTOCORRECT = "autocorrect"
        private const val GLIDE = "glide"
        private const val VIBRATION = "vibration"
        private const val BOTTOM_OFFSET = "bottom_offset"
        private const val EMOJI_FONT_VERSION = "emoji_font_version"
    }
}
