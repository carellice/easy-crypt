package com.easycrypt.keyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Editor con la tastiera di sistema e risultato aggiornato dopo ogni modifica. */
class TextCryptActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var input: EditText
    private var output = ""
    private var encrypt = true
    @Volatile private var revision = 0
    // Accessibili solo dal worker: si evita PBKDF2 a ogni tasto.
    private var sessionPassword = ""
    private var sessionKey: EasyCrypt.SessionKey? = null
    private val update = Runnable { process() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_crypt)
        applyInsets(findViewById(R.id.root))
        input = findViewById(R.id.input)
        encrypt = savedInstanceState?.getBoolean("encrypt", true) ?: true
        val modes = findViewById<RadioGroup>(R.id.modes)
        modes.check(if (encrypt) R.id.encrypt else R.id.decrypt)
        modes.setOnCheckedChangeListener { _, id ->
            encrypt = id == R.id.encrypt
            schedule()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { schedule() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        findViewById<Button>(R.id.paste).setOnClickListener {
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (text.isNullOrEmpty()) Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            else {
                input.setText(text)
                input.setSelection(input.text.length)
            }
        }
        findViewById<Button>(R.id.clear).setOnClickListener { input.text.clear() }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.copy).setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("EasyCrypt", output))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.share).setOnClickListener {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, output), null))
        }
        if (savedInstanceState != null) input.setText(savedInstanceState.getString("input", ""))
        else if (intent.action == Intent.ACTION_SEND) input.setText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
    }

    override fun onResume() {
        super.onResume()
        schedule() // Ricalcola anche quando si cambia la password nelle impostazioni.
    }

    private fun schedule() {
        revision++
        handler.removeCallbacks(update)
        showResult(null, false) // Non permette di copiare un risultato ormai superato.
        handler.postDelayed(update, 180)
    }

    private fun process() {
        val text = input.text.toString().let { if (encrypt) it else it.trim() }
        if (text.isBlank()) return
        val password = PasswordStore(this).get()
        if (password.isEmpty()) {
            showResult(getString(R.string.no_password), false)
            return
        }
        val current = revision
        val mode = encrypt
        showResult(getString(R.string.working), false)
        worker.execute {
            if (current != revision) return@execute
            val result = runCatching {
                if (mode) {
                    if (sessionPassword != password || sessionKey == null) {
                        sessionKey = EasyCrypt.newSessionKey(password)
                        sessionPassword = password
                    }
                    EasyCrypt.encrypt(text, sessionKey!!)
                } else EasyCrypt.decrypt(text, password)
            }.getOrNull()
            runOnUiThread {
                if (current != revision || isDestroyed) return@runOnUiThread
                showResult(result ?: getString(if (mode) R.string.encrypt_error else R.string.decrypt_error), result != null)
            }
        }
    }

    private fun showResult(text: String?, success: Boolean) {
        output = if (success) text.orEmpty() else ""
        findViewById<TextView>(R.id.result_title).apply {
            setText(if (encrypt) R.string.encrypted_title else R.string.decrypted_title)
            visibility = if (text == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.result).apply {
            this.text = text
            visibility = if (text == null) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.result_actions).visibility = if (success) View.VISIBLE else View.GONE
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("input", input.text.toString())
        state.putBoolean("encrypt", encrypt)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        revision++
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}
