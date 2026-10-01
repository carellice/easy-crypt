package com.easycrypt.keyboard

import android.content.Context
import android.content.res.Configuration

/** Palette in stile Gboard (tema predefinito con bordi dei tasti), chiara e scura. */
class KeyboardTheme(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val functionalKey: Int,
    val functionalKeyPressed: Int,
    val keyShadow: Int,
    val text: Int,
    val hint: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val popup: Int,
    val icon: Int,
    val chip: Int,
) {
    companion object {
        private val light = KeyboardTheme(
            background = 0xFFE8EAED.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD5D8DD.toInt(),
            functionalKey = 0xFFCBCFD6.toInt(),
            functionalKeyPressed = 0xFFB4B9C1.toInt(),
            keyShadow = 0x38000000,
            text = 0xFF1F1F1F.toInt(),
            hint = 0xFF5F6368.toInt(),
            accent = 0xFF1A73E8.toInt(),
            accentPressed = 0xFF1557B0.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            icon = 0xFF444746.toInt(),
            chip = 0xFFD3E3FD.toInt(),
        )
        private val dark = KeyboardTheme(
            background = 0xFF202124.toInt(),
            key = 0xFF4A4D51.toInt(),
            keyPressed = 0xFF6B6F74.toInt(),
            functionalKey = 0xFF303236.toInt(),
            functionalKeyPressed = 0xFF50545A.toInt(),
            keyShadow = 0x50000000,
            text = 0xFFE8EAED.toInt(),
            hint = 0xFFBDC1C6.toInt(),
            accent = 0xFF8AB4F8.toInt(),
            accentPressed = 0xFFAECBFA.toInt(),
            onAccent = 0xFF202124.toInt(),
            popup = 0xFF5F6368.toInt(),
            icon = 0xFFC4C7C5.toInt(),
            chip = 0xFF004A77.toInt(),
        )

        fun from(context: Context): KeyboardTheme {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) dark else light
        }
    }
}
