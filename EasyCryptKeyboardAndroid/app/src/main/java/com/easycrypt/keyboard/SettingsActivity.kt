package com.easycrypt.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.graphics.Typeface
import android.widget.EditText
import android.graphics.Rect
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import java.io.File
import android.widget.TextView
import android.widget.Toast

/** Configurazione: abilitazione della tastiera e password di cifratura. */
class SettingsActivity : Activity() {
    private lateinit var passwordStore: PasswordStore
    private lateinit var imm: InputMethodManager
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        passwordStore = PasswordStore(this)
        imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            }
            insets
        }
        // Quando la tastiera si apre o cambia altezza, tiene visibile il campo in cui si sta scrivendo.
        val scroll = findViewById<ScrollView>(R.id.root)
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scroll.post {
                val focused = currentFocus as? EditText ?: return@post
                val rect = Rect(0, 0, focused.width, focused.height)
                scroll.offsetDescendantRectToMyCoords(focused, rect)
                val visibleBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
                val margin = (16 * resources.displayMetrics.density).toInt()
                if (rect.bottom + margin > visibleBottom) scroll.scrollBy(0, rect.bottom + margin - visibleBottom)
            }
        }

        prefs = Prefs(this)
        setUpOptions()
        if (savedInstanceState == null && !prefs.onboardingDone) startActivity(Intent(this, OnboardingActivity::class.java))
        findViewById<Button>(R.id.onboarding).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        findViewById<Button>(R.id.text_crypt).setOnClickListener {
            if (passwordStore.get().isEmpty()) {
                Toast.makeText(this, R.string.no_password, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, TextCryptActivity::class.java))
            }
        }
        findViewById<Button>(R.id.enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.select).setOnClickListener { imm.showInputMethodPicker() }

        val password = findViewById<EditText>(R.id.password)
        findViewById<CheckBox>(R.id.show_password).setOnCheckedChangeListener { _, checked ->
            val variation = if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.inputType = InputType.TYPE_CLASS_TEXT or variation
            password.setSelection(password.text.length)
        }
        findViewById<Button>(R.id.save).setOnClickListener {
            val value = password.text.toString()
            passwordStore.set(value)
            password.text.clear()
            Toast.makeText(this, if (value.isEmpty()) R.string.password_removed else R.string.password_saved, Toast.LENGTH_SHORT).show()
            updateStatus()
        }
    }

    private fun setUpOptions() {
        findViewById<Switch>(R.id.number_row).apply {
            isChecked = prefs.numberRow
            setOnCheckedChangeListener { _, checked -> prefs.numberRow = checked }
        }
        findViewById<Switch>(R.id.autocorrect).apply {
            isChecked = prefs.autocorrect
            setOnCheckedChangeListener { _, checked -> prefs.autocorrect = checked }
        }

        findViewById<Switch>(R.id.vibration).apply {
            isChecked = prefs.vibration
            setOnCheckedChangeListener { _, checked -> prefs.vibration = checked }
        }
        findViewById<Switch>(R.id.glide).apply {
            isChecked = prefs.glide
            setOnCheckedChangeListener { _, checked -> prefs.glide = checked }
        }

        val offsetLabel = findViewById<TextView>(R.id.bottom_offset_label)
        offsetLabel.text = getString(R.string.option_bottom_offset, prefs.bottomOffset)
        findViewById<SeekBar>(R.id.bottom_offset).apply {
            max = Prefs.MAX_BOTTOM_OFFSET
            progress = prefs.bottomOffset
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    offsetLabel.text = getString(R.string.option_bottom_offset, progress)
                    if (fromUser) prefs.bottomOffset = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }

        findViewById<Button>(R.id.emoji_font_choose).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            startActivityForResult(intent, REQUEST_EMOJI_FONT)
        }
        findViewById<Button>(R.id.emoji_font_reset).setOnClickListener {
            prefs.emojiFont.delete()
            prefs.emojiFontVersion++
            updateStatus()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_EMOJI_FONT || resultCode != RESULT_OK || uri == null) return
        // Copia il font nell'area privata dell'app e controlla che sia leggibile come font.
        val temp = File(filesDir, "emoji_font.tmp")
        val valid = try {
            contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use(input::copyTo) }
            Typeface.Builder(temp).build() != null
        } catch (e: Exception) {
            false
        }
        if (valid && temp.renameTo(prefs.emojiFont)) {
            prefs.emojiFontVersion++
        } else {
            temp.delete()
            Toast.makeText(this, R.string.emoji_font_invalid, Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Il selettore delle tastiere è una finestra di sistema: alla chiusura si riottiene il focus.
        if (hasFocus) updateStatus()
    }

    private fun updateStatus() {
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        findViewById<TextView>(R.id.enable_status).setText(if (enabled) R.string.status_enabled else R.string.status_disabled)
        findViewById<TextView>(R.id.select_status).setText(if (selected) R.string.status_selected else R.string.status_not_selected)
        findViewById<Button>(R.id.select).isEnabled = enabled
        findViewById<TextView>(R.id.emoji_font_status).setText(
            if (prefs.emojiFont.exists()) R.string.emoji_font_custom else R.string.emoji_font_system
        )
        findViewById<Button>(R.id.emoji_font_reset).isEnabled = prefs.emojiFont.exists()
        findViewById<TextView>(R.id.password_status).setText(
            if (passwordStore.get().isEmpty()) R.string.password_unset else R.string.password_set
        )
    }

    private companion object {
        const val REQUEST_EMOJI_FONT = 1
    }
}
