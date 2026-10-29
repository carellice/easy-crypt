package com.easycrypt.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Cifra o decifra un testo ricevuto da un'altra app:
 * - voci "Decifra" e "Cifra" nel menu di selezione del testo (ACTION_PROCESS_TEXT), con sostituzione
 *   della selezione se il testo è modificabile;
 * - testo condiviso con l'app (ACTION_SEND): si sceglie se cifrare o decifrare.
 */
class ProcessTextActivity : LockedActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var password = ""
    private var shared = false
    private var readOnly = true
    private var awaitingPassword = false
    private var incoming = ""
    private var selectedMode: Boolean? = null
    private var revision = 0

    override fun onCreateUnlocked(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_process_text)
        findViewById<Button>(R.id.close).setOnClickListener { finish() }

        awaitingPassword = savedInstanceState?.getBoolean("awaitingPassword", false) ?: false
        shared = intent.action == Intent.ACTION_SEND
        val text = if (shared) {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        } else {
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
            intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        }
        if (text.isBlank()) {
            finish()
            return
        }

        incoming = text
        selectedMode = if (savedInstanceState?.containsKey("selectedMode") == true) savedInstanceState.getBoolean("selectedMode") else null
    }

    override fun onResumeUnlocked() {
        if (isFinishing) return
        password = PasswordStore(this).get()
        if (password.isEmpty()) {
            Toast.makeText(this, R.string.no_password, Toast.LENGTH_SHORT).show()
            if (awaitingPassword) {
                finish()
                return
            }
            awaitingPassword = true
            startActivityForResult(Intent(this, SettingsActivity::class.java), 1)
            return
        }

        awaitingPassword = false
        if (shared) {
            // Testo condiviso: mostra il testo e lascia scegliere l'operazione.
            findViewById<TextView>(R.id.title).setText(R.string.app_name)
            findViewById<TextView>(R.id.result).text = incoming
            val encrypt = findViewById<Button>(R.id.encrypt)
            val decrypt = findViewById<Button>(R.id.decrypt)
            encrypt.visibility = View.VISIBLE
            decrypt.visibility = View.VISIBLE
            encrypt.setOnClickListener { process(incoming, encrypt = true) }
            decrypt.setOnClickListener { process(incoming, encrypt = false) }
            selectedMode?.let { process(incoming, it) }
        } else {
            process(incoming, encrypt = intent.component?.className?.endsWith("EncryptTextAlias") == true)
        }
    }

    private fun process(text: String, encrypt: Boolean) {
        if (!isAppUnlocked) return
        selectedMode = encrypt
        val current = ++revision
        val currentPassword = password
        val result = findViewById<TextView>(R.id.result)
        val copy = findViewById<Button>(R.id.copy)
        val action = findViewById<Button>(R.id.replace)
        findViewById<View>(R.id.encrypt).visibility = View.GONE
        findViewById<View>(R.id.decrypt).visibility = View.GONE
        findViewById<TextView>(R.id.title).setText(if (encrypt) R.string.encrypted_title else R.string.decrypted_title)
        result.setText(R.string.working)

        worker.execute {
            val output = runCatching {
                if (encrypt) EasyCrypt.encrypt(text, currentPassword) else EasyCrypt.decrypt(text.trim(), currentPassword)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || !isAppUnlocked || current != revision) return@runOnUiThread
                if (output == null) {
                    result.setText(if (encrypt) R.string.encrypt_error else R.string.decrypt_error)
                    return@runOnUiThread
                }
                result.text = output
                copy.visibility = View.VISIBLE
                copy.setOnClickListener {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("EasyCrypt", output))
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
                    finish()
                }
                if (shared) {
                    action.setText(R.string.share)
                    action.visibility = View.VISIBLE
                    action.setOnClickListener {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, output)
                        startActivity(Intent.createChooser(send, null))
                        finish()
                    }
                } else if (!readOnly) {
                    action.visibility = View.VISIBLE
                    action.setOnClickListener {
                        setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, output))
                        finish()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (isContentCreated) {
            state.putBoolean("awaitingPassword", awaitingPassword)
            selectedMode?.let { state.putBoolean("selectedMode", it) }
        }
        super.onSaveInstanceState(state)
    }

    override fun onAppLocked() {
        revision++
        password = ""
        if (!isContentCreated) return
        findViewById<TextView>(R.id.result).text = ""
        findViewById<View>(R.id.copy).visibility = View.GONE
        findViewById<View>(R.id.replace).visibility = View.GONE
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
