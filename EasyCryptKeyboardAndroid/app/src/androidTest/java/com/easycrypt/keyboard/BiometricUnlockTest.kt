package com.easycrypt.keyboard

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Su emulatore: registrare l'impronta 1 e inviare `adb emu finger touch 1` ai prompt. */
@RunWith(AndroidJUnit4::class)
class BiometricUnlockTest {
    private val context get() = ApplicationProvider.getApplicationContext<EasyCryptApplication>()

    @Before fun prepare() {
        assumeTrue(BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS)
        reset()
    }

    @After fun reset() {
        context.getSharedPreferences("app_lock", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("easycrypt", Context.MODE_PRIVATE).edit().clear().commit()
        context.lockSession.stopped(changingConfiguration = false)
    }

    @Test fun enableAndDisableRequireSuccessfulAuthentication() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<CheckBox>(R.id.biometric_lock).performClick() }
            await(scenario) { AppLock(it).enabled && it.findViewById<CheckBox>(R.id.biometric_lock).isChecked }
            scenario.onActivity { it.findViewById<CheckBox>(R.id.biometric_lock).performClick() }
            await(scenario) { !AppLock(it).enabled && !it.findViewById<CheckBox>(R.id.biometric_lock).isChecked }
        }
    }

    @Test fun unlockPreservesSharedTextAndLocksAgainAfterBackground() {
        AppLock(context).enabled = true
        val intent = Intent(context, TextCryptActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, "testo riservato")
        ActivityScenario.launch<TextCryptActivity>(intent).use { scenario ->
            await(scenario) { it.findViewById<View>(R.id.input)?.isShown == true }
            scenario.onActivity {
                assertEquals("testo riservato", it.findViewById<EditText>(R.id.input).text.toString())
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(context.lockSession.authenticated)
            scenario.moveToState(Lifecycle.State.RESUMED)
            await(scenario) { it.findViewById<View>(R.id.input)?.isShown == true }
            scenario.recreate()
            scenario.onActivity {
                assertEquals("testo riservato", it.findViewById<EditText>(R.id.input).text.toString())
            }
        }
    }

    @Test fun selectedTextIsDecryptedOnlyAfterAuthentication() {
        AppLock(context).enabled = true
        PasswordStore(context).set("password di prova")
        val encrypted = EasyCrypt.encrypt("messaggio segreto", "password di prova")
        val intent = Intent(context, ProcessTextActivity::class.java).setAction(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT, encrypted)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        ActivityScenario.launch<ProcessTextActivity>(intent).use { scenario ->
            await(scenario) { it.findViewById<TextView>(R.id.result)?.text?.toString() == "messaggio segreto" }
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.copy).isShown) }
        }
    }

    private fun <T : android.app.Activity> await(scenario: ActivityScenario<T>, condition: (T) -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Autenticazione biometrica non completata entro 20 secondi")
    }
}
