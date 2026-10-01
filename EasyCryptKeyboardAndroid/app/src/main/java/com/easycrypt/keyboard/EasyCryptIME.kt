package com.easycrypt.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.AbsListView
import android.widget.ListView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class EasyCryptIME : InputMethodService(), KeyboardView.Listener {

    private class Correction(val original: String, val corrected: String, var separator: String = "")

    private lateinit var passwordStore: PasswordStore
    private lateinit var prefs: Prefs
    private lateinit var autocorrect: Autocorrect
    private lateinit var clipboard: ClipboardManager
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var theme: KeyboardTheme
    private var keyboard: KeyboardView? = null
    private var emojiPanel: View? = null
    private var lockButton: ImageView? = null
    private var preview: TextView? = null
    private var decryptChip: TextView? = null
    private var encryptChip: TextView? = null
    private var clipboardChip: TextView? = null
    private var resultPanel: View? = null
    private var resultView: TextView? = null
    private var resultActions: List<View> = emptyList()
    private var suggestionRow: View? = null
    private var suggestionViews: List<TextView> = emptyList()
    private var suggestions: Autocorrect.Suggestions? = null

    // Pannello emoji: categorie (caricate alla prima apertura) ed emoji della scheda selezionata.
    private val emojiPaint = Paint()
    private var emojiCategories: List<List<String>>? = null
    private var emojiRows: List<Any> = emptyList()
    private var emojiSections = IntArray(0)
    private var openEmojiPanel: () -> Unit = {}

    private var password = ""
    private var cryptoOn = false
    private var passwordField = false
    private val cryptoActive get() = cryptoOn && !passwordField && password.isNotEmpty()

    // Messaggio cifrato in corso: testo in chiaro digitato e ultimo testo cifrato scritto nel campo.
    private val plain = StringBuilder()
    private var lastCipher = ""
    private var session: EasyCrypt.SessionKey? = null
    private var spare: EasyCrypt.SessionKey? = null
    private var deriving = false

    private var hasSelection = false
    private var selStart = -1
    private var selEnd = -1

    // Scorrimento dal tasto cancella: testo prima del cursore, posizione del cursore e caratteri selezionati.
    private var deleting = false
    private var deleteInPlain = false
    private var deleteBefore = ""
    private var deleteAnchor = -1
    private var deleteCount = 0
    private var clipboardDirty = true
    private var clipboardText: String? = null
    private var resultText: String? = null
    private var lastSpaceTime = 0L

    /** Campo di testo normale (non password, indirizzi, ecc.): solo lì si corregge. */
    private var correctableField = false
    private val autocorrectActive get() = correctableField && prefs.autocorrect
    private var lastCorrection: Correction? = null

    /** Ultima parola inserita a scorrimento (con l'eventuale spazio davanti): cancella la elimina per intero. */
    private var lastGesture: String? = null
    private var gestureSuggestions: Autocorrect.Suggestions? = null

    /** Opzioni con cui è stata costruita la vista: se cambiano va ricostruita. */
    private var builtWith = ""
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyPrefs()
        keyboard?.glideEnabled = correctableField && prefs.glide
        keyboard?.vibrationEnabled = prefs.vibration
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        clipboardDirty = true
        if (isInputViewShown) updateStrip()
    }

    override fun onCreate() {
        super.onCreate()
        passwordStore = PasswordStore(this)
        prefs = Prefs(this)
        autocorrect = Autocorrect(this)
        prefs.shared.registerOnSharedPreferenceChangeListener(prefsListener)
        clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        prefs.shared.unregisterOnSharedPreferenceChangeListener(prefsListener)
        worker.shutdown()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false

    // Mostra la tastiera anche se è collegata una tastiera fisica (come fa Gboard).
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    // ---- vista ----

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun prefsSignature() = "${prefs.numberRow}/${prefs.bottomOffset}/${prefs.emojiFontVersion}"

    /** Ricostruisce la tastiera quando le opzioni vengono cambiate dall'app. */
    private fun applyPrefs() {
        if (keyboard == null || builtWith == prefsSignature()) return
        setInputView(onCreateInputView())
        currentInputEditorInfo?.let { keyboard?.setEnterIcon(enterIcon(it)) }
        updateShift()
        updateStrip()
    }

    override fun onCreateInputView(): View {
        builtWith = prefsSignature()
        val bottomOffset = dp(prefs.bottomOffset)
        theme = KeyboardTheme.from(this)
        window.window?.navigationBarColor = theme.background

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            clipChildren = false
            clipToPadding = false
        }
        // Da Android 15 la finestra dell'IME può essere edge-to-edge: lascia spazio alla barra di navigazione.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            v.setPadding(0, 0, 0, bottom + bottomOffset)
            insets
        }

        root.addView(buildResultPanel(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(buildPreview(), LinearLayout.LayoutParams(MATCH, dp(34)))
        root.addView(buildStrip(), LinearLayout.LayoutParams(MATCH, dp(STRIP_HEIGHT)))

        root.setPadding(0, 0, 0, bottomOffset)
        val view = KeyboardView(this, theme, prefs.numberRow)
        view.vibrationEnabled = prefs.vibration
        view.listener = this
        view.overdrawTop = dp(STRIP_HEIGHT).toFloat()
        keyboard = view
        val body = FrameLayout(this).apply { clipChildren = false }
        body.addView(view, FrameLayout.LayoutParams(MATCH, WRAP))
        body.addView(buildEmojiPanel(view.keyboardHeight), FrameLayout.LayoutParams(MATCH, view.keyboardHeight))
        root.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        return root
    }

    private fun ripple() = RippleDrawable(ColorStateList.valueOf(0x33808080), null, null)

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(theme.icon)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = ripple()
        contentDescription = getString(description)
        setOnClickListener { onClick() }
    }

    private fun chip(label: Int, onClick: () -> Unit) = TextView(this).apply {
        setText(label)
        setTextColor(theme.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(theme.chip)
        }
        visibility = View.GONE
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(32)).apply { marginEnd = dp(8) }
    }

    private fun buildStrip(): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), 0)
        }
        lockButton = iconButton(R.drawable.ic_lock_open, R.string.toggle_crypto, ::toggleCrypto)
        strip.addView(lockButton, LinearLayout.LayoutParams(dp(STRIP_HEIGHT), dp(STRIP_HEIGHT)))

        decryptChip = chip(R.string.action_decrypt) { decrypt(selectedText()) }
        encryptChip = chip(R.string.action_encrypt) { encryptSelection() }
        clipboardChip = chip(R.string.chip_decrypt_clipboard) {
            decrypt(clipboardText)
            clipboardText = null
            updateStrip()
        }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chips.addView(decryptChip)
        chips.addView(encryptChip)
        chips.addView(clipboardChip)

        // Suggerimenti in stile Gboard: tre parole, quella centrale in evidenza.
        val suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        suggestionViews = List(3) { index ->
            if (index > 0) {
                val divider = View(this).apply { setBackgroundColor(theme.functionalKey) }
                suggestions.addView(divider, LinearLayout.LayoutParams(dp(1), dp(20)))
            }
            TextView(this).apply {
                setTextColor(theme.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                if (index == 1) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.START
                background = ripple()
                setOnClickListener { onSuggestionPicked(index) }
                suggestions.addView(this, LinearLayout.LayoutParams(0, MATCH, 1f))
            }
        }
        suggestionRow = suggestions

        val middle = FrameLayout(this)
        middle.addView(chips, FrameLayout.LayoutParams(MATCH, MATCH))
        middle.addView(suggestions, FrameLayout.LayoutParams(MATCH, MATCH))
        strip.addView(middle, LinearLayout.LayoutParams(0, MATCH, 1f))

        val settings = iconButton(R.drawable.ic_settings, R.string.settings, ::openSettings)
        strip.addView(settings, LinearLayout.LayoutParams(dp(STRIP_HEIGHT), dp(STRIP_HEIGHT)))
        return strip
    }

    /** Riga sopra la barra: mostra in chiaro il testo che si sta scrivendo cifrato. */
    private fun buildPreview(): View {
        val view = TextView(this).apply {
            setTextColor(theme.text)
            setHintTextColor(theme.hint)
            setHint(R.string.crypto_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            visibility = View.GONE
        }
        preview = view
        return view
    }

    private fun buildResultPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), 0)
            visibility = View.GONE
        }
        resultView = TextView(this).apply {
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 6
            movementMethod = ScrollingMovementMethod()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(theme.key)
            }
        }
        panel.addView(resultView, LinearLayout.LayoutParams(MATCH, WRAP))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(0, dp(6), 0, 0)
        }
        val copy = chip(R.string.copy) {
            resultText?.let {
                clipboard.setPrimaryClip(ClipData.newPlainText("EasyCrypt", it))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
        }
        val insert = chip(R.string.insert) {
            resultText?.let { currentInputConnection?.commitText(it, 1) }
            hideResult()
        }
        val close = iconButton(R.drawable.ic_close, R.string.close, ::hideResult)
        resultActions = listOf(copy, insert)
        actions.addView(copy)
        actions.addView(insert)
        actions.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        panel.addView(actions, LinearLayout.LayoutParams(MATCH, WRAP))
        resultPanel = panel
        return panel
    }

    private fun buildEmojiPanel(height: Int): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            visibility = View.GONE
        }
        // Font emoji scelto dall'utente nelle impostazioni (se presente e valido).
        val emojiTypeface = prefs.emojiFont.takeIf { it.exists() }?.let {
            try {
                Typeface.createFromFile(it)
            } catch (e: Exception) {
                null
            }
        }
        emojiCategories = null
        emojiPaint.typeface = emojiTypeface

        // Come su Gboard: un unico elenco che scorre, con le emoji recenti in cima e un titolo per categoria.
        val emojiAdapter = object : BaseAdapter() {
            override fun getCount() = emojiRows.size
            override fun getItem(position: Int) = emojiRows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 2
            override fun getItemViewType(position: Int) = if (emojiRows[position] is String) 0 else 1
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = false

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val item = emojiRows[position]
                if (item is String) {
                    val title = convertView as? TextView ?: TextView(this@EasyCryptIME).apply {
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(theme.hint)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setPadding(dp(12), dp(8), dp(12), dp(2))
                    }
                    title.text = item
                    return title
                }
                val row = convertView as? LinearLayout ?: LinearLayout(this@EasyCryptIME).apply {
                    orientation = LinearLayout.HORIZONTAL
                    repeat(EMOJI_COLUMNS) {
                        val cell = TextView(this@EasyCryptIME).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                            setTextColor(theme.text)
                            gravity = Gravity.CENTER
                            if (emojiTypeface != null) typeface = emojiTypeface
                            background = ripple()
                            setOnClickListener { view -> pickEmoji((view as TextView).text.toString()) }
                        }
                        addView(cell, LinearLayout.LayoutParams(0, dp(46), 1f))
                    }
                }
                val emojis = item as List<*>
                for (i in 0 until EMOJI_COLUMNS) {
                    val cell = row.getChildAt(i) as TextView
                    cell.text = emojis.getOrNull(i) as? String ?: ""
                    cell.isClickable = i < emojis.size
                }
                return row
            }
        }
        val list = ListView(this).apply {
            adapter = emojiAdapter
            divider = null
            isVerticalScrollBarEnabled = false
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        panel.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Barra in basso: ABC, una scheda per categoria (la prima sono le recenti), cancella.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        val abc = TextView(this).apply {
            text = "ABC"
            setTextColor(theme.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = ripple()
            setOnClickListener { onEmoji() }
        }
        bar.addView(abc, LinearLayout.LayoutParams(dp(48), dp(40)))
        val tabs = EMOJI_TABS.mapIndexed { index, icon ->
            TextView(this).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTextColor(theme.text)
                gravity = Gravity.CENTER
                if (emojiTypeface != null) typeface = emojiTypeface
                setOnClickListener { list.setSelection(emojiSections[index]) }
                bar.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f))
            }
        }
        val backspace = iconButton(R.drawable.ic_backspace, R.string.backspace, ::onBackspace)
        bar.addView(backspace, LinearLayout.LayoutParams(dp(48), dp(40)))
        panel.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // La scheda evidenziata segue la categoria che si sta guardando.
        var highlighted = -1
        fun highlight(firstRow: Int) {
            var tab = 0
            for (i in 1 until emojiSections.size) if (emojiSections[i] <= firstRow) tab = i
            if (tab == highlighted) return
            highlighted = tab
            tabs.forEachIndexed { i, view ->
                view.background = if (i != tab) null else GradientDrawable().apply {
                    cornerRadius = dp(17).toFloat()
                    setColor(theme.functionalKey)
                }
            }
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) = Unit
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = highlight(first)
        })

        // A ogni apertura: le recenti aggiornate in cima (mentre il pannello è aperto non si spostano sotto il dito).
        openEmojiPanel = {
            val categories = emojiCategories ?: loadEmoji().also { emojiCategories = it }
            val titles = resources.getStringArray(R.array.emoji_categories)
            val rows = ArrayList<Any>()
            val sections = IntArray(EMOJI_TABS.size)
            val recent = prefs.recentEmoji.take(RECENT_EMOJI)
            if (recent.isNotEmpty()) {
                rows.add(titles[0])
                rows.addAll(recent.chunked(EMOJI_COLUMNS))
            }
            categories.forEachIndexed { i, emojis ->
                if (i + 1 < sections.size) sections[i + 1] = rows.size
                rows.add(titles.getOrElse(i + 1) { "" })
                rows.addAll(emojis.chunked(EMOJI_COLUMNS))
            }
            emojiRows = rows
            emojiSections = sections
            emojiAdapter.notifyDataSetChanged()
            list.setSelection(0)
            highlighted = -1
            highlight(0)
        }
        emojiPanel = panel
        return panel
    }

    private fun pickEmoji(emoji: String) {
        if (emoji.isEmpty()) return
        onText(emoji)
        prefs.recentEmoji = (listOf(emoji) + prefs.recentEmoji.filter { it != emoji }).take(RECENT_EMOJI)
    }

    /** Legge le emoji per categoria (assets/emoji.txt) tenendo solo quelle che il font in uso sa disegnare. */
    private fun loadEmoji(): List<List<String>> = try {
        assets.open("emoji.txt").bufferedReader().readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line -> line.split(' ').filter { it.isNotEmpty() && emojiPaint.hasGlyph(it) } }
    } catch (e: Exception) {
        emptyList()
    }

    // ---- ciclo di vita dell'input ----

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyPrefs()
        lastCorrection = null
        lastGesture = null
        val stored = passwordStore.get()
        if (stored != password) {
            password = stored
            session = null
            spare = null
            plain.setLength(0)
            lastCipher = ""
            if (password.isEmpty()) cryptoOn = false
        }
        ensureSpare()

        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        passwordField = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }

        correctableField = inputClass == InputType.TYPE_CLASS_TEXT && !passwordField &&
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            variation != InputType.TYPE_TEXT_VARIATION_URI &&
            variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_FILTER

        keyboard?.let { view ->
            view.previewEnabled = !passwordField
            view.glideEnabled = correctableField && prefs.glide
            view.setEnterIcon(enterIcon(info))
            if (!restarting) {
                if (inputClass == InputType.TYPE_CLASS_TEXT || inputClass == 0) view.showLetters() else view.showSymbols()
                showEmoji(false)
            }
        }
        if (!restarting) resetSession()
        hasSelection = info.initialSelStart != info.initialSelEnd
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        deleting = false
        updateShift()
        updateStrip()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        hideResult()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        // La selezione fatta scorrendo dal tasto cancella non deve far comparire «Decifra» / «Cifra».
        if (deleting) return
        hasSelection = newSelStart != newSelEnd
        // Campo svuotato dall'app (messaggio inviato): il messaggio cifrato in corso è chiuso.
        // Spostare il cursore o selezionare non lo chiude: si ricontrolla alla prossima battuta.
        if (cryptoActive && lastCipher.isNotEmpty() && newSelStart == 0 && newSelEnd == 0) {
            val ic = currentInputConnection
            if (ic?.getTextAfterCursor(1, 0)?.isEmpty() == true) resetSession()
        }
        updateShift()
        updateStrip()
    }

    private fun enterIcon(info: EditorInfo): KeyboardView.EnterIcon {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return KeyboardView.EnterIcon.ENTER
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> KeyboardView.EnterIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> KeyboardView.EnterIcon.SEND
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> KeyboardView.EnterIcon.NEXT
            EditorInfo.IME_ACTION_DONE -> KeyboardView.EnterIcon.DONE
            else -> KeyboardView.EnterIcon.ENTER
        }
    }

    // ---- scrittura cifrata in tempo reale ----

    /** Tiene sempre pronta una chiave già derivata (PBKDF2 è lento) per il prossimo messaggio. */
    private fun ensureSpare() {
        if (spare != null || deriving || password.isEmpty()) return
        deriving = true
        val pw = password
        worker.execute {
            val key = EasyCrypt.newSessionKey(pw)
            main.post {
                deriving = false
                if (pw != password) {
                    ensureSpare()
                } else if (session == null) {
                    session = key
                    if (cryptoActive && plain.isNotEmpty()) render()
                    ensureSpare()
                } else {
                    spare = key
                }
            }
        }
    }

    /** Inizia un nuovo messaggio: nuovo salt e nuova chiave, testo in chiaro azzerato. */
    private fun resetSession() {
        val unused = plain.isEmpty() && lastCipher.isEmpty()
        plain.setLength(0)
        lastCipher = ""
        if (!unused || session == null) {
            session = spare
            spare = null
            ensureSpare()
        }
        updateStrip()
    }

    /** Se il campo non termina più con il nostro testo cifrato (inviato, cancellato, cursore spostato) il messaggio è chiuso. */
    private fun validateSession() {
        if (lastCipher.isEmpty()) return
        val before = currentInputConnection?.getTextBeforeCursor(lastCipher.length, 0) ?: return
        if (before.toString() != lastCipher) resetSession()
    }

    /** Sostituisce nel campo il testo cifrato precedente con la cifratura del testo in chiaro attuale. */
    private fun render() {
        val ic = currentInputConnection ?: return
        val key = session ?: return
        val cipher = if (plain.isEmpty()) "" else EasyCrypt.encrypt(plain.toString(), key)
        ic.beginBatchEdit()
        if (lastCipher.isNotEmpty()) ic.deleteSurroundingText(lastCipher.length, 0)
        if (cipher.isNotEmpty()) ic.commitText(cipher, 1)
        ic.endBatchEdit()
        lastCipher = cipher
    }

    private fun toggleCrypto() {
        if (password.isEmpty()) {
            needPassword()
            return
        }
        cryptoOn = !cryptoOn
        resetSession()
        updateShift()
    }

    // ---- tasti ----

    private fun isDoubleSpace(before: CharSequence?): Boolean {
        if (before == null || before.length < 2) return false
        if (SystemClock.uptimeMillis() - lastSpaceTime > 1000) return false
        return before[before.length - 1] == ' ' && before[before.length - 2].isLetterOrDigit()
    }

    /**
     * Corregge l'ultima parola prima del cursore. Nella scrittura cifrata modifica solo il testo in chiaro:
     * chi chiama deve poi aggiornare il campo con render().
     */
    private fun autocorrectLastWord(): Correction? {
        if (!autocorrectActive) return null
        val ic = currentInputConnection ?: return null
        val word = wordBeforeCursor()
        val corrected = autocorrect.correct(word) ?: return null
        if (cryptoActive) {
            plain.setLength(plain.length - word.length)
            plain.append(corrected)
        } else {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(corrected, 1)
        }
        return Correction(word, corrected)
    }

    /** Backspace subito dopo una correzione automatica: ripristina la parola com'era stata scritta. */
    private fun undoCorrection(): Boolean {
        val correction = lastCorrection ?: return false
        lastCorrection = null
        val ic = currentInputConnection ?: return false
        val applied = correction.corrected + correction.separator
        val restored = correction.original + correction.separator
        if (cryptoActive) {
            if (!plain.endsWith(applied)) return false
            plain.setLength(plain.length - applied.length)
            plain.append(restored)
            render()
            updateStrip()
        } else {
            if (ic.getTextBeforeCursor(applied.length, 0)?.toString() != applied) return false
            ic.beginBatchEdit()
            ic.deleteSurroundingText(applied.length, 0)
            ic.commitText(restored, 1)
            ic.endBatchEdit()
        }
        autocorrect.ignore(correction.original)
        return true
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        if (cryptoActive) validateSession()
        val doubleSpace = text == " " && isDoubleSpace(if (cryptoActive) plain else ic.getTextBeforeCursor(2, 0))
        val separator = text.length == 1 && text[0] in SEPARATORS
        lastGesture = null
        ic.beginBatchEdit()
        val correction = if (separator && !doubleSpace) autocorrectLastWord() else null
        if (cryptoActive) {
            if (doubleSpace) {
                plain.setLength(plain.length - 1)
                plain.append(". ")
            } else {
                plain.append(text)
            }
            render()
            updateStrip()
        } else if (doubleSpace) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(text, 1)
        }
        ic.endBatchEdit()
        lastCorrection = correction?.also { it.separator = text }
        lastSpaceTime = if (text == " ") SystemClock.uptimeMillis() else 0
        updateShift()
        if (!cryptoActive) updateStrip()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (cryptoActive) validateSession()
        if (undoCorrection() || undoGesture()) {
            updateShift()
            return
        }
        if (cryptoActive) {
            if (plain.isNotEmpty()) {
                plain.setLength(plain.offsetByCodePoints(plain.length, -1))
                render()
                updateStrip()
                updateShift()
                return
            }
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (cryptoActive) validateSession()
        lastCorrection = null
        lastGesture = null
        if (autocorrectLastWord() != null && cryptoActive) {
            render()
            updateStrip()
        }
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
            return
        }
        if (cryptoActive) {
            if (plain.isNotEmpty()) {
                plain.append('\n')
                render()
                updateStrip()
                updateShift()
                return
            }
        }
        if (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            ic.commitText("\n", 1)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    // ---- gesti di scorrimento ----

    override fun onGesture(xs: FloatArray, ys: FloatArray, caps: Int) {
        val view = keyboard ?: return
        val ic = currentInputConnection ?: return
        val words = autocorrect.decodeGesture(xs, ys, view.letterX, view.letterY, view.keyUnit).map {
            when (caps) {
                2 -> it.uppercase()
                1 -> it.replaceFirstChar(Char::uppercase)
                else -> it
            }
        }
        if (words.isEmpty()) return
        if (cryptoActive) validateSession()
        // Come Gboard: lo spazio tra una parola e l'altra viene aggiunto da solo.
        val before: CharSequence? = if (cryptoActive) plain else ic.getTextBeforeCursor(1, 0)
        val needsSpace = !before.isNullOrEmpty() && !before.last().isWhitespace() && before.last() !in "([{'\"«"
        val text = (if (needsSpace) " " else "") + words[0]
        if (cryptoActive) {
            plain.append(text)
            render()
        } else {
            ic.commitText(text, 1)
        }
        lastCorrection = null
        lastGesture = text
        gestureSuggestions = Autocorrect.Suggestions(
            words[0], listOf(words.getOrElse(1) { "" }, words[0], words.getOrElse(2) { "" }), null
        )
        updateShift()
        updateStrip()
    }

    /** Cancella subito dopo una parola inserita a scorrimento: la elimina per intero. */
    private fun undoGesture(): Boolean {
        val text = lastGesture ?: return false
        lastGesture = null
        val ic = currentInputConnection ?: return false
        if (cryptoActive) {
            if (!plain.endsWith(text)) return false
            plain.setLength(plain.length - text.length)
            render()
            updateStrip()
        } else {
            if (ic.getTextBeforeCursor(text.length, 0)?.toString() != text) return false
            ic.deleteSurroundingText(text.length, 0)
        }
        return true
    }

    override fun onCursorMove(delta: Int) {
        // Nella scrittura cifrata il campo contiene il testo cifrato: spostarsi al suo interno non ha senso.
        if (cryptoActive && plain.isNotEmpty()) return
        lastCorrection = null
        lastGesture = null
        sendDownUpKeyEvents(if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
    }

    override fun onDeleteSelect(words: Int) {
        val ic = currentInputConnection ?: return
        if (!deleting) {
            if (cryptoActive) validateSession()
            deleting = true
            // Nella scrittura cifrata si lavora sul testo in chiaro (evidenziato nell'anteprima),
            // altrimenti le parole vengono selezionate nel campo di testo.
            deleteInPlain = cryptoActive && plain.isNotEmpty()
            deleteBefore = if (deleteInPlain) plain.toString()
            else ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty() + ic.getSelectedText(0)?.toString().orEmpty()
            deleteAnchor = maxOf(selStart, selEnd)
        }
        var start = deleteBefore.length
        repeat(words) {
            while (start > 0 && deleteBefore[start - 1].isWhitespace()) start--
            while (start > 0 && !deleteBefore[start - 1].isWhitespace()) start--
        }
        if (start < deleteBefore.length && Character.isLowSurrogate(deleteBefore[start])) start++
        deleteCount = deleteBefore.length - start
        if (deleteInPlain) {
            updateStrip()
        } else if (deleteAnchor >= deleteCount) {
            ic.setSelection(deleteAnchor - deleteCount, deleteAnchor)
        }
    }

    override fun onDeleteCommit() {
        if (!deleting) return
        deleting = false
        val count = deleteCount
        deleteCount = 0
        lastCorrection = null
        lastGesture = null
        val ic = currentInputConnection ?: return
        if (deleteInPlain) {
            if (count > 0 && count <= plain.length) {
                plain.setLength(plain.length - count)
                render()
            }
        } else {
            ic.beginBatchEdit()
            if (deleteAnchor >= 0) ic.setSelection(deleteAnchor, deleteAnchor)
            if (count > 0) ic.deleteSurroundingText(count, 0)
            ic.endBatchEdit()
        }
        hasSelection = false
        updateShift()
        updateStrip()
    }

    override fun onEmoji() = showEmoji(emojiPanel?.visibility != View.VISIBLE)

    private fun showEmoji(show: Boolean) {
        if (show) openEmojiPanel()
        emojiPanel?.visibility = if (show) View.VISIBLE else View.GONE
        keyboard?.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }

    override fun onSpaceLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun updateShift() {
        val info = currentInputEditorInfo ?: return
        val caps = if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) {
            false
        } else if (cryptoActive) {
            TextUtils.getCapsMode(plain, plain.length, info.inputType) != 0
        } else {
            (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        }
        keyboard?.setAutoShift(caps)
    }

    // ---- barra superiore, decifratura ----

    private fun updateStrip() {
        val lock = lockButton ?: return
        if (clipboardDirty) {
            clipboardDirty = false
            val text = try {
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
            } catch (e: Exception) {
                null
            }
            clipboardText = text?.takeIf(EasyCrypt::looksEncrypted)
        }
        lock.visibility = if (passwordField) View.INVISIBLE else View.VISIBLE
        lock.setImageResource(if (cryptoActive) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lock.imageTintList = ColorStateList.valueOf(if (cryptoActive) theme.accent else theme.icon)

        decryptChip?.visibility = if (hasSelection) View.VISIBLE else View.GONE
        encryptChip?.visibility = if (hasSelection) View.VISIBLE else View.GONE
        clipboardChip?.visibility = if (!hasSelection && clipboardText != null) View.VISIBLE else View.GONE
        preview?.apply {
            visibility = if (cryptoActive) View.VISIBLE else View.GONE
            val shown = SpannableString(plain.toString().replace('\n', '⏎'))
            if (deleting && deleteInPlain && deleteCount in 1..shown.length) {
                // Parole che verranno cancellate al rilascio del tasto cancella.
                shown.setSpan(BackgroundColorSpan(theme.chip), shown.length - deleteCount, shown.length, 0)
            }
            text = shown
        }

        // Suggerimenti per la parola in corso (al posto dei tasti di decifratura mentre si scrive).
        val word = if (correctableField && !hasSelection) wordBeforeCursor() else ""
        val current = when {
            word.isEmpty() -> null
            gestureSuggestions?.typed == word -> gestureSuggestions
            autocorrectActive -> autocorrect.suggest(word)
            else -> null
        }
        suggestions = current
        current?.items?.forEachIndexed { i, item -> suggestionViews[i].text = item }
        suggestionRow?.visibility = if (current != null) View.VISIBLE else View.GONE
        (decryptChip?.parent as? View)?.visibility = if (current != null) View.GONE else View.VISIBLE
    }

    /** La parola (solo lettere) che termina al cursore. */
    private fun wordBeforeCursor(): String {
        val before: CharSequence =
            (if (cryptoActive) plain else currentInputConnection?.getTextBeforeCursor(48, 0)) ?: return ""
        var start = before.length
        while (start > 0 && before[start - 1].isLetter()) start--
        return before.substring(start)
    }

    /** Tocco su un suggerimento: sostituisce la parola in corso e aggiunge uno spazio. */
    private fun onSuggestionPicked(index: Int) {
        val current = suggestions ?: return
        val picked = current.items.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return
        val ic = currentInputConnection ?: return
        if (cryptoActive) validateSession()
        val word = wordBeforeCursor()
        if (word != current.typed) return
        // Scegliere la parola così com'è stata scritta significa che non va più corretta.
        if (picked == word && current.correction != null) autocorrect.ignore(word)
        if (cryptoActive) {
            plain.setLength(plain.length - word.length)
            plain.append(picked).append(' ')
            render()
        } else {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText("$picked ", 1)
            ic.endBatchEdit()
        }
        lastCorrection = null
        lastGesture = null
        lastSpaceTime = SystemClock.uptimeMillis()
        updateShift()
        updateStrip()
    }

    private fun selectedText() = currentInputConnection?.getSelectedText(0)?.toString()

    private fun decrypt(text: String?) {
        if (text.isNullOrBlank()) return
        if (password.isEmpty()) {
            needPassword()
            return
        }
        showResult(getString(R.string.working), null)
        val pw = password
        worker.execute {
            val result = runCatching { EasyCrypt.decrypt(text, pw) }
            main.post {
                val decrypted = result.getOrNull()
                showResult(decrypted ?: getString(R.string.decrypt_error), decrypted)
            }
        }
    }

    private fun encryptSelection() {
        val text = selectedText()
        if (text.isNullOrEmpty()) return
        if (password.isEmpty()) {
            needPassword()
            return
        }
        val pw = password
        worker.execute {
            val result = runCatching { EasyCrypt.encrypt(text, pw) }
            main.post {
                val encrypted = result.getOrNull()
                if (encrypted == null) {
                    Toast.makeText(this, R.string.encrypt_error, Toast.LENGTH_SHORT).show()
                } else if (selectedText() == text) {
                    currentInputConnection?.commitText(encrypted, 1)
                }
            }
        }
    }

    private fun showResult(message: String, payload: String?) {
        resultText = payload
        resultView?.text = message
        resultView?.scrollTo(0, 0)
        resultActions.forEach { it.visibility = if (payload != null) View.VISIBLE else View.GONE }
        resultPanel?.visibility = View.VISIBLE
    }

    private fun hideResult() {
        resultText = null
        resultView?.text = ""
        resultPanel?.visibility = View.GONE
    }

    private fun needPassword() {
        Toast.makeText(this, R.string.no_password, Toast.LENGTH_SHORT).show()
        openSettings()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val STRIP_HEIGHT = 44
        const val SEPARATORS = " .,;:!?"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val RECENT_EMOJI = 32
        const val EMOJI_COLUMNS = 8

        /** Schede del pannello emoji: recenti, poi le categorie nell'ordine di assets/emoji.txt. */
        val EMOJI_TABS = listOf("🕒", "😀", "👋", "🐻", "🍔", "🚗", "⚽", "💡", "🔣", "🏁")
    }
}
