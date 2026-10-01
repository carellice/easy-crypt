package com.easycrypt.keyboard

import android.app.Activity
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
class ProcessTextActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var password = ""
    private var shared = false
    private var readOnly = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_text)
        findViewById<Button>(R.id.close).setOnClickListener { finish() }

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

        password = PasswordStore(this).get()
        if (password.isEmpty()) {
            Toast.makeText(this, R.string.no_password, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
            return
        }

        if (shared) {
            // Testo condiviso: mostra il testo e lascia scegliere l'operazione.
            findViewById<TextView>(R.id.title).setText(R.string.app_name)
            findViewById<TextView>(R.id.result).text = text
            val encrypt = findViewById<Button>(R.id.encrypt)
            val decrypt = findViewById<Button>(R.id.decrypt)
            encrypt.visibility = View.VISIBLE
            decrypt.visibility = View.VISIBLE
            encrypt.setOnClickListener { process(text, encrypt = true) }
            decrypt.setOnClickListener { process(text, encrypt = false) }
        } else {
            process(text, encrypt = intent.component?.className?.endsWith("EncryptTextAlias") == true)
        }
    }

    private fun process(text: String, encrypt: Boolean) {
        val result = findViewById<TextView>(R.id.result)
        val copy = findViewById<Button>(R.id.copy)
        val action = findViewById<Button>(R.id.replace)
        findViewById<View>(R.id.encrypt).visibility = View.GONE
        findViewById<View>(R.id.decrypt).visibility = View.GONE
        findViewById<TextView>(R.id.title).setText(if (encrypt) R.string.encrypted_title else R.string.decrypted_title)
        result.setText(R.string.working)

        worker.execute {
            val output = runCatching {
                if (encrypt) EasyCrypt.encrypt(text, password) else EasyCrypt.decrypt(text, password)
            }.getOrNull()
            runOnUiThread {
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

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
