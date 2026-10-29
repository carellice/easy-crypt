package com.easycrypt.keyboard

import android.os.Bundle
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle

/** Tutti gli ingressi passano qui prima di leggere password o mostrare testi. */
abstract class LockedActivity : FragmentActivity() {
    private val appLock by lazy { AppLock(this) }
    private val session get() = (application as EasyCryptApplication).lockSession
    private lateinit var prompt: BiometricPrompt
    private var contentCreated = false
    private var originalState: Bundle? = null
    private var cover: View? = null
    private var authenticating = false
    private var lockChange: Boolean? = null
    private var openingInternalActivity = false
    protected val isContentCreated get() = contentCreated
    protected val isAppUnlocked get() = contentCreated && cover == null && (!appLock.enabled || session.authenticated)

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        originalState = savedInstanceState
        authenticating = savedInstanceState?.getBoolean("lock_prompt", false) ?: false
        lockChange = if (savedInstanceState?.containsKey("lock_change") == true) savedInstanceState.getBoolean("lock_change") else null
        secureWindow()
        prompt = BiometricPrompt(this, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                lockChange?.let { appLock.enabled = it }
                lockChange = null
                session.authenticate()
                secureWindow()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) reveal()
                onAppLockChanged()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                authenticating = false
                if (lockChange != null) {
                    lockChange = null
                    secureWindow()
                    onAppLockChanged()
                    Toast.makeText(this@LockedActivity, errString, Toast.LENGTH_LONG).show()
                } else cover?.findViewById<TextView>(R.id.lock_status)?.text = errString
            }

            override fun onAuthenticationFailed() {
                cover?.findViewById<TextView>(R.id.lock_status)?.setText(R.string.lock_not_recognized)
            }
        })
        if (!appLock.enabled || session.authenticated) createContent() else conceal()
    }

    final override fun onResume() {
        super.onResume()
        secureWindow()
        if (!appLock.enabled || session.authenticated) reveal()
        else {
            conceal()
            if (!authenticating) authenticate()
        }
    }

    override fun onPause() {
        if (appLock.enabled) {
            conceal()
            val returning = isFinishing && (application as EasyCryptApplication).returningInsideApp(this)
            // Blocca già in onPause: anche un passaggio molto rapido ad altre app richiede lo sblocco.
            session.paused(openingInternalActivity || returning || isChangingConfigurations)
        }
        openingInternalActivity = false
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        openingInternalActivity = intent.component?.packageName == packageName
        try {
            super.startActivityForResult(intent, requestCode, options)
        } catch (error: Exception) {
            openingInternalActivity = false
            throw error
        }
    }

    private fun createContent() {
        if (contentCreated) return
        // setContentView della schermata rimuove anche la copertura iniziale.
        cover = null
        contentCreated = true
        onCreateUnlocked(originalState)
        originalState = null
    }

    private fun reveal() {
        createContent()
        val content = findViewById<ViewGroup>(android.R.id.content)
        cover?.let { content.removeView(it) }
        cover = null
        for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.VISIBLE
        onResumeUnlocked()
    }

    private fun conceal() {
        if (cover != null) return
        onAppLocked()
        val content = findViewById<ViewGroup>(android.R.id.content)
        for (i in 0 until content.childCount) content.getChildAt(i).visibility = View.INVISIBLE
        cover = LayoutInflater.from(this).inflate(R.layout.activity_locked, content, false).also {
            content.addView(it)
            applyInsets(it)
            it.findViewById<Button>(R.id.unlock).setOnClickListener { authenticate() }
            it.findViewById<Button>(R.id.lock_close).setOnClickListener { finish() }
        }
    }

    private fun authenticate() {
        if (authenticating) return
        authenticating = true
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            // WEAK | CREDENTIAL è supportato anche su Android 8–10.
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build())
    }

    protected fun changeAppLock(enable: Boolean) {
        if (authenticating) return
        if (enable && BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, R.string.lock_unavailable, Toast.LENGTH_LONG).show()
            onAppLockChanged()
            return
        }
        // Anche la disattivazione richiede una conferma del proprietario.
        lockChange = enable
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        authenticating = true
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(if (enable) R.string.lock_enable_title else R.string.lock_disable_title))
            .setAllowedAuthenticators(if (enable) BIOMETRIC_WEAK else BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
        if (enable) info.setNegativeButtonText(getString(R.string.cancel))
        prompt.authenticate(info.build())
    }

    private fun secureWindow() {
        if (appLock.enabled || lockChange != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onSaveInstanceState(state: Bundle) {
        if (!contentCreated) originalState?.let { state.putAll(it) }
        state.putBoolean("lock_prompt", authenticating)
        lockChange?.let { state.putBoolean("lock_change", it) }
        super.onSaveInstanceState(state)
    }

    protected abstract fun onCreateUnlocked(savedInstanceState: Bundle?)
    protected open fun onResumeUnlocked() = Unit
    protected open fun onAppLocked() = Unit
    protected open fun onAppLockChanged() = Unit
}
