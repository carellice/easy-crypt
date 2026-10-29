package com.easycrypt.keyboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockTest {
    private val context get() = ApplicationProvider.getApplicationContext<EasyCryptApplication>()

    @Before fun prepare() {
        context.getSharedPreferences("app_lock", Context.MODE_PRIVATE).edit().clear().commit()
        context.lockSession.stopped(changingConfiguration = false)
    }

    @After fun clean() { prepare() }

    @Test fun disabledByDefault() {
        ActivityScenario.launch(TextCryptActivity::class.java).use { scenario ->
            scenario.onActivity { assertTrue(it.findViewById<View>(R.id.input).isShown) }
        }
    }

    @Test fun editorAndSharedTextStayHiddenBeforeAuthentication() {
        AppLock(context).enabled = true
        val intent = Intent(context, TextCryptActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "testo riservato")
        ActivityScenario.launch<TextCryptActivity>(intent).use { scenario ->
            scenario.onActivity {
                assertNull(it.findViewById<View>(R.id.input))
                assertNotNull(it.findViewById<View>(R.id.unlock))
                assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            scenario.recreate()
            scenario.onActivity { assertNull(it.findViewById<View>(R.id.input)) }
        }
    }

    @Test fun bothSelectionActionsRequireAuthentication() {
        AppLock(context).enabled = true
        for (name in listOf("ProcessTextActivity", "EncryptTextAlias")) {
            val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .setComponent(ComponentName(context, "com.easycrypt.keyboard.$name"))
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "testo riservato")
            ActivityScenario.launch<ProcessTextActivity>(intent).use { scenario ->
                scenario.onActivity {
                    assertNull(it.findViewById<View>(R.id.result))
                    assertNotNull(it.findViewById<View>(R.id.unlock))
                }
            }
        }
    }

    @Test fun settingsCannotBypassLock() {
        AppLock(context).enabled = true
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity {
                assertNull(it.findViewById<View>(R.id.password))
                assertNotNull(it.findViewById<View>(R.id.unlock))
            }
        }
    }

    @Test fun returningFromBackgroundLocksExistingEditor() {
        AppLock(context).enabled = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.lockSession.authenticate() }
        ActivityScenario.launch(TextCryptActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.input).setText("testo riservato") }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertFalse(it.findViewById<View>(R.id.input).isShown)
                assertNotNull(it.findViewById<View>(R.id.unlock))
                assertFalse(context.lockSession.authenticated)
            }
        }
    }

    @Test fun briefPauseRequiresNewAuthenticationWithoutWaitingForStop() {
        AppLock(context).enabled = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync { context.lockSession.authenticate() }
        ActivityScenario.launch(TextCryptActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.STARTED)
            assertFalse(context.lockSession.authenticated)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity {
                assertFalse(it.findViewById<View>(R.id.input).isShown)
                assertNotNull(it.findViewById<View>(R.id.unlock))
            }
        }
    }
}
