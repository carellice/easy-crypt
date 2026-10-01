package com.easycrypt.keyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Introduzione al primo avvio: spiega cosa fa l'app e guida nella configurazione di tastiera e password. */
class OnboardingActivity : Activity() {
    private lateinit var imm: InputMethodManager
    private lateinit var passwordStore: PasswordStore
    private lateinit var gestures: GestureDetector

    private val pages = ArrayList<View>()
    private val dots = ArrayList<View>()
    private var current = 0
    private lateinit var next: TextView
    private lateinit var skip: TextView

    private var dark = false
    private var accent = 0
    private var onAccent = 0
    private var accentContainer = 0
    private var card = 0
    private var textPrimary = 0
    private var textSecondary = 0
    private val done = 0xFF34A853.toInt()

    /** Aggiornano i tre passi della configurazione in base allo stato attuale. */
    private val stepUpdaters = ArrayList<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        passwordStore = PasswordStore(this)

        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        accent = if (dark) 0xFF8AB4F8.toInt() else 0xFF1A73E8.toInt()
        onAccent = if (dark) 0xFF202124.toInt() else Color.WHITE
        accentContainer = if (dark) 0xFF28374F.toInt() else 0xFFE8F0FE.toInt()
        card = if (dark) 0xFF2D2F31.toInt() else 0xFFF1F3F4.toInt()
        textPrimary = if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt()
        textSecondary = if (dark) 0xFFBDC1C6.toInt() else 0xFF5F6368.toInt()

        pages += infoPage(
            R.drawable.ic_lock, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body,
            bullets(R.string.onboarding_welcome_point_1, R.string.onboarding_welcome_point_2, R.string.onboarding_welcome_point_3, numbered = false),
        )
        pages += infoPage(
            R.drawable.ic_lock, R.string.onboarding_write_title, R.string.onboarding_write_body,
            demo(R.string.onboarding_demo_typed, R.string.onboarding_demo_plain, R.string.onboarding_demo_sent, R.string.onboarding_demo_cipher, cipherLast = true),
        )
        pages += infoPage(
            R.drawable.ic_lock_open, R.string.onboarding_read_title, R.string.onboarding_read_body,
            demo(R.string.onboarding_demo_received, R.string.onboarding_demo_cipher, R.string.onboarding_demo_read, R.string.onboarding_demo_plain, cipherLast = false),
        )
        pages += infoPage(
            R.drawable.ic_send, R.string.onboarding_free_title, R.string.onboarding_free_body,
            bullets(R.string.onboarding_free_point_1, R.string.onboarding_free_point_2, R.string.onboarding_free_point_3, numbered = true),
        )
        pages += setupPage()

        val container = FrameLayout(this)
        pages.forEachIndexed { i, page ->
            page.visibility = if (i == 0) View.VISIBLE else View.GONE
            container.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        skip = TextView(this).apply {
            setText(R.string.onboarding_skip)
            setTextColor(accent)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(accentContainer), null, rounded(Color.WHITE, 24))
            setOnClickListener { go(pages.lastIndex) }
        }
        val top = FrameLayout(this).apply {
            setPadding(dp(8), dp(8), dp(8), 0)
            addView(skip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END))
        }

        val indicator = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        repeat(pages.size) {
            val dot = View(this)
            dots += dot
            indicator.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        }
        next = pill(R.string.onboarding_next) { if (current == pages.lastIndex) finish() else go(current + 1) }
        val bottom = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(20))
            addView(indicator, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(next)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(container, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH, WRAP))
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < dp(64) || abs(dx) < 2 * abs(e2.y - e1.y)) return false
                go(current + if (dx < 0) 1 else -1)
                return true
            }
        })
        showPage()
        // Da Android 13 il tasto indietro non passa più da onBackPressed.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    /** Indietro torna alla pagina precedente; dalla prima chiude l'introduzione. */
    private fun back() {
        if (current > 0) go(current - 1) else finish()
    }

    override fun finish() {
        Prefs(this).onboardingDone = true
        super.finish()
    }

    override fun onResume() {
        super.onResume()
        updateSteps()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Il selettore delle tastiere è una finestra di sistema: alla chiusura si riottiene il focus.
        if (hasFocus) updateSteps()
    }

    private fun updateSteps() = stepUpdaters.forEach { it() }

    private fun go(index: Int) {
        if (index == current || index !in pages.indices) return
        val direction = if (index > current) 1 else -1
        val old = pages[current]
        val new = pages[index]
        pages.forEach {
            it.animate().cancel()
            if (it !== old) it.visibility = View.GONE
        }
        old.animate().alpha(0f).translationX(-direction * dp(48f)).setDuration(160)
            .withEndAction { old.visibility = View.GONE }
        new.alpha = 0f
        new.translationX = direction * dp(48f)
        new.visibility = View.VISIBLE
        new.animate().alpha(1f).translationX(0f).setDuration(220)
        current = index
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
        showPage()
    }

    private fun showPage() {
        val last = current == pages.lastIndex
        skip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        next.setText(if (last) R.string.onboarding_start else R.string.onboarding_next)
        dots.forEachIndexed { i, dot ->
            dot.background = rounded(if (i == current) accent else (if (dark) 0xFF5F6368.toInt() else 0xFFDADCE0.toInt()), 4)
            dot.layoutParams = dot.layoutParams.apply { width = dp(if (i == current) 24 else 8) }
        }
    }

    // --- Pagine ---

    private fun infoPage(icon: Int, title: Int, body: Int, extra: View): View {
        val column = column()
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accentContainer) }
            addView(
                ImageView(context).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(accent) },
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER),
            )
        }
        column.addView(badge, LinearLayout.LayoutParams(dp(112), dp(112)))
        column.addView(title(title), margin(top = 28))
        column.addView(body(body), margin(top = 12))
        column.addView(extra, margin(top = 28, width = MATCH))
        return scroll(column)
    }

    /** Esempio: un testo e ciò che diventa. */
    private fun demo(firstLabel: Int, first: Int, secondLabel: Int, second: Int, cipherLast: Boolean): View {
        fun line(label: Int, text: Int, cipher: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(caption(label))
            addView(TextView(context).apply {
                setText(text)
                textSize = if (cipher) 14f else 17f
                setTextColor(if (cipher) accent else textPrimary)
                if (cipher) typeface = Typeface.MONOSPACE
                setPadding(0, dp(4), 0, 0)
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(line(firstLabel, first, !cipherLast))
            addView(TextView(context).apply {
                text = "↓"
                textSize = 20f
                setTextColor(accent)
                gravity = Gravity.CENTER
            }, margin(top = 8, bottom = 8, width = MATCH))
            addView(line(secondLabel, second, cipherLast))
        }
    }

    private fun bullets(vararg points: Int, numbered: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        points.forEachIndexed { i, point ->
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(card, 20)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(circle(if (numbered) "${i + 1}" else "✓", accent, onAccent))
                addView(TextView(context).apply {
                    setText(point)
                    textSize = 15f
                    setTextColor(textPrimary)
                    setLineSpacing(dp(2f), 1f)
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            }
            addView(row, margin(top = if (i == 0) 0 else 10, width = MATCH))
        }
    }

    private fun setupPage(): View {
        val column = column()
        column.addView(title(R.string.onboarding_setup_title))
        column.addView(body(R.string.onboarding_setup_body), margin(top = 12))

        column.addView(
            step(1, R.string.onboarding_step_enable, R.string.onboarding_step_enable_note, R.string.onboarding_step_enable_action,
                isDone = { keyboardEnabled() }) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            margin(top = 24, width = MATCH),
        )
        column.addView(
            step(2, R.string.onboarding_step_select, R.string.onboarding_step_select_note, R.string.onboarding_step_select_action,
                isDone = { keyboardSelected() }) {
                if (keyboardEnabled()) imm.showInputMethodPicker()
                else Toast.makeText(this, R.string.status_disabled, Toast.LENGTH_SHORT).show()
            },
            margin(top = 10, width = MATCH),
        )

        val password = EditText(this).apply {
            setHint(R.string.password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            typeface = Typeface.DEFAULT // il tipo password userebbe un carattere a larghezza fissa
        }
        column.addView(
            step(3, R.string.onboarding_step_password, R.string.onboarding_step_password_note, R.string.password_save,
                isDone = { passwordStore.get().isNotEmpty() }, field = password) {
                val value = password.text.toString()
                if (value.isEmpty()) {
                    Toast.makeText(this, R.string.password_hint, Toast.LENGTH_SHORT).show()
                } else {
                    passwordStore.set(value)
                    password.text.clear()
                    password.clearFocus()
                    imm.hideSoftInputFromWindow(password.windowToken, 0)
                    Toast.makeText(this, R.string.password_saved, Toast.LENGTH_SHORT).show()
                    updateSteps()
                }
            },
            margin(top = 10, width = MATCH),
        )
        column.addView(caption(R.string.onboarding_setup_optional).apply { gravity = Gravity.CENTER }, margin(top = 16, width = MATCH))
        return scroll(column)
    }

    private fun step(number: Int, title: Int, note: Int, action: Int, isDone: () -> Boolean, field: EditText? = null, onAction: () -> Unit): View {
        val badge = circle("$number", accent, onAccent)
        val noteView = caption(note)
        val button = pill(action, onAction)
        stepUpdaters += {
            val ok = isDone()
            badge.text = if (ok) "✓" else "$number"
            badge.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ok) done else accent) }
            badge.setTextColor(if (ok) Color.WHITE else onAccent)
            if (field == null) {
                // Fatto: resta solo la spunta. La password invece si può sempre sostituire.
                button.visibility = if (ok) View.GONE else View.VISIBLE
                noteView.setText(if (ok) R.string.onboarding_step_done else note)
            } else {
                noteView.setText(if (ok) R.string.password_set else note)
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 20)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(badge)
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        setText(title)
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(textPrimary)
                    })
                    addView(noteView)
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(14) })
            })
            if (field != null) addView(field, margin(top = 6, width = MATCH))
            addView(button, margin(top = 10).apply { gravity = Gravity.END })
        }
    }

    private fun keyboardEnabled() = imm.enabledInputMethodList.any { it.packageName == packageName }

    private fun keyboardSelected() =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true

    // --- Elementi ---

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(16), dp(28), dp(16))
    }

    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
    }

    private fun title(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 26f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textPrimary)
        gravity = Gravity.CENTER
    }

    private fun body(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 16f
        setTextColor(textSecondary)
        gravity = Gravity.CENTER
        setLineSpacing(dp(3f), 1f)
    }

    private fun caption(text: Int) = TextView(this).apply {
        setText(text)
        textSize = 13f
        setTextColor(textSecondary)
    }

    private fun circle(text: String, color: Int, textColor: Int) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(textColor)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
    }

    private fun pill(text: Int, onClick: () -> Unit) = TextView(this).apply {
        setText(text)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onAccent)
        gravity = Gravity.CENTER
        minWidth = dp(96)
        setPadding(dp(24), dp(12), dp(24), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(accent, 24), null)
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius.toFloat())
    }

    private fun margin(top: Int = 0, bottom: Int = 0, width: Int = WRAP) =
        LinearLayout.LayoutParams(width, WRAP).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
