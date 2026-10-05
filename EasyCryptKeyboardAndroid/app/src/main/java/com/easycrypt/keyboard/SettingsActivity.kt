package com.easycrypt.keyboard

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Password e istruzioni per usare EasyCrypt con la propria tastiera. */
class SettingsActivity : Activity() {
    private lateinit var passwordStore: PasswordStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        passwordStore = PasswordStore(this)
        applyInsets(findViewById(R.id.root))
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

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.password_status).setText(
            if (passwordStore.get().isEmpty()) R.string.password_unset else R.string.password_set
        )
    }
}
