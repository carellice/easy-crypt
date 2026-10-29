package com.easycrypt.keyboard

import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** Password, istruzioni e aggiornamento dell'app dalle release su GitHub. */
class SettingsActivity : LockedActivity() {
    private lateinit var passwordStore: PasswordStore
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private var release: Updater.Release? = null

    override fun onCreateUnlocked(savedInstanceState: Bundle?) {
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
        findViewById<CheckBox>(R.id.biometric_lock).apply {
            isChecked = AppLock(this@SettingsActivity).enabled
            setOnClickListener {
                val enable = isChecked
                isChecked = AppLock(this@SettingsActivity).enabled
                changeAppLock(enable)
            }
        }
        updateStatus = findViewById(R.id.update_status)
        updateButton = findViewById(R.id.update)
        updateStatus.text = getString(R.string.update_installed, installedVersion())
        updateButton.setOnClickListener { release?.let(::install) ?: check() }
        handleInstallStatus(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isAppUnlocked) handleInstallStatus(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun installedVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    private fun check() {
        updateButton.isEnabled = false
        updateStatus.setText(R.string.update_checking)
        val current = installedVersion()
        worker.execute {
            val result = runCatching { Updater.check(current) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                updateButton.isEnabled = true
                result.onSuccess { found ->
                    release = found
                    if (found == null) {
                        updateStatus.text = getString(R.string.update_latest, current)
                    } else {
                        updateStatus.text = getString(R.string.update_available, found.version)
                        updateButton.text = getString(R.string.update_install, found.version)
                    }
                }.onFailure { showError(it) }
            }
        }
    }

    private fun install(found: Updater.Release) {
        if (!packageManager.canRequestPackageInstalls()) {
            // Android chiede una volta di permettere a EasyCrypt di installare app.
            updateStatus.setText(R.string.update_permission)
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateButton.isEnabled = false
        updateStatus.text = getString(R.string.update_downloading, 0)
        worker.execute {
            val result = runCatching {
                Updater.install(applicationContext, found) { percent ->
                    runOnUiThread { updateStatus.text = getString(R.string.update_downloading, percent) }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onFailure {
                    updateButton.isEnabled = true
                    showError(it)
                }
            }
        }
    }

    /** Esito della sessione di PackageInstaller: conferma da mostrare all'utente o errore. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != Updater.ACTION_STATUS) return
        setIntent(Intent(this, SettingsActivity::class.java))  // non rielabora l'esito dopo una rotazione
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                updateStatus.setText(R.string.update_confirm)
                confirm?.let { startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> updateStatus.text = getString(R.string.update_installed, installedVersion())
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                updateButton.isEnabled = true
                updateStatus.setText(R.string.update_cancelled)
            }
            else -> {
                updateButton.isEnabled = true
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "errore sconosciuto"
                updateStatus.text = getString(R.string.update_error, message)
            }
        }
    }

    private fun showError(error: Throwable) {
        updateStatus.text = getString(R.string.update_error, error.message ?: error.javaClass.simpleName)
    }

    override fun onResumeUnlocked() {
        updateStatus()
        onAppLockChanged()
        handleInstallStatus(intent)
    }

    override fun onAppLockChanged() {
        if (isContentCreated) findViewById<CheckBox>(R.id.biometric_lock).isChecked = AppLock(this).enabled
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.password_status).setText(
            if (passwordStore.get().isEmpty()) R.string.password_unset else R.string.password_set
        )
    }
}
