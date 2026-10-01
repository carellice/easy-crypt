package com.easycrypt.keyboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Pagina dell'app per cifrare o decifrare un testo scritto o incollato, senza usare la tastiera EasyCrypt. */
class TextCryptActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var input: EditText
    private var output = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_text_crypt)

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

        input = findViewById(R.id.input)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        findViewById<Button>(R.id.paste).setOnClickListener {
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)
            if (text.isNullOrEmpty()) {
                Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            } else {
                input.setText(text)
                input.setSelection(input.text.length)
            }
        }
        findViewById<Button>(R.id.clear).setOnClickListener {
            input.text.clear()
            showResult(null, null)
        }
        findViewById<Button>(R.id.encrypt).setOnClickListener { process(encrypt = true) }
        findViewById<Button>(R.id.decrypt).setOnClickListener { process(encrypt = false) }
        findViewById<Button>(R.id.copy).setOnClickListener {
            clipboard.setPrimaryClip(ClipData.newPlainText("EasyCrypt", output))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.share).setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, output)
            startActivity(Intent.createChooser(send, null))
        }
    }

    private fun process(encrypt: Boolean) {
        // Un testo cifrato incollato può avere spazi o a capo attorno: per decifrare si tolgono.
        val text = input.text.toString().let { if (encrypt) it else it.trim() }
        if (text.isBlank()) {
            Toast.makeText(this, R.string.text_crypt_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val password = PasswordStore(this).get()
        if (password.isEmpty()) {
            Toast.makeText(this, R.string.no_password, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val title = if (encrypt) R.string.encrypted_title else R.string.decrypted_title
        showResult(title, getString(R.string.working), actions = false)
        worker.execute {
            val result = runCatching {
                if (encrypt) EasyCrypt.encrypt(text, password) else EasyCrypt.decrypt(text, password)
            }.getOrNull()
            runOnUiThread {
                if (result == null) {
                    showResult(title, getString(if (encrypt) R.string.encrypt_error else R.string.decrypt_error), actions = false)
                } else {
                    output = result
                    showResult(title, result)
                }
            }
        }
    }

    private fun showResult(title: Int?, text: String?, actions: Boolean = true) {
        val titleView = findViewById<TextView>(R.id.result_title)
        val resultView = findViewById<TextView>(R.id.result)
        val visibility = if (text == null) View.GONE else View.VISIBLE
        titleView.visibility = visibility
        resultView.visibility = visibility
        findViewById<View>(R.id.result_actions).visibility = if (text != null && actions) View.VISIBLE else View.GONE
        if (title != null) titleView.setText(title)
        resultView.text = text
        // Porta in vista il risultato, che può finire sotto la tastiera.
        if (text != null) findViewById<ScrollView>(R.id.root).let { it.post { it.smoothScrollTo(0, titleView.top) } }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
