package com.easycrypt.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.SparseArray
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot

/** Tastiera disegnata su Canvas con l'aspetto di Gboard. */
class KeyboardView(
    context: Context,
    private val theme: KeyboardTheme,
    numberRow: Boolean = false,
) : View(context) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onEmoji()
        fun onSpaceLongPress()

        /** Parola tracciata scorrendo il dito sulle lettere. [caps]: 0 minuscola, 1 iniziale maiuscola, 2 tutta maiuscola. */
        fun onGesture(xs: FloatArray, ys: FloatArray, caps: Int)

        /** Scorrimento sulla barra spaziatrice: sposta il cursore di un carattere (-1 a sinistra, +1 a destra). */
        fun onCursorMove(delta: Int)

        /**
         * Scorrimento verso sinistra dal tasto cancella: seleziona le ultime [words] parole prima del cursore
         * (0 = nessuna, tornando indietro col dito).
         */
        fun onDeleteSelect(words: Int)

        /** Rilascio dopo lo scorrimento dal tasto cancella: elimina le parole selezionate. */
        fun onDeleteCommit()
    }

    enum class EnterIcon(val drawable: Int) {
        ENTER(R.drawable.ic_enter), SEARCH(R.drawable.ic_search), SEND(R.drawable.ic_send),
        DONE(R.drawable.ic_done), NEXT(R.drawable.ic_next)
    }

    private enum class Shift { OFF, ON, LOCKED }

    private enum class Mode { KEY, GLIDE, CURSOR, DELETE }

    private class Pointer(var key: Key?, val downX: Float, val downY: Float) {
        var consumed = false
        val startKey = key
        var mode = Mode.KEY

        /** Posizione dell'ultimo passo (spostamento del cursore o parola cancellata). */
        var stepX = downX

        /** Parole selezionate scorrendo dal tasto cancella. */
        var words = 0
        var released = false

        /** Tracciato della digitazione a scorrimento. */
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
    }

    private class AltPopup(val pointerId: Int, val items: List<String>, val rect: RectF, var selected: Int)

    var listener: Listener? = null
    var previewEnabled = true

    /** Vibrazione alla pressione dei tasti. */
    var vibrationEnabled = true

    /** Digitazione a scorrimento sulle lettere. */
    var glideEnabled = false

    /** Centri dei tasti da 'a' a 'z' e larghezza di un tasto, per riconoscere le parole tracciate. */
    val letterX = FloatArray(26)
    val letterY = FloatArray(26)
    var keyUnit = 0f
        private set

    /** Spazio disponibile sopra la vista (la barra superiore) in cui possono sporgere i popup. */
    var overdrawTop = 0f

    private val density = resources.displayMetrics.density
    private val landscape = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
    private val rowHeight = (if (landscape) 42 else if (numberRow) 50 else 54) * density

    /** Altezza dell'area dei tasti: è fissa, le pagine con meno righe hanno tasti un po' più alti. */
    private val keysHeight = rowHeight * (if (numberRow) 5 else 4)
    private val letterRows = if (numberRow) KeyboardLayouts.lettersWithNumbers else KeyboardLayouts.letters
    private val topPadding = 2 * density
    private val bottomPadding = 8 * density
    private val sidePadding = 2 * density
    private val gapX = 3 * density
    private val gapY = 4 * density
    private val radius = 6 * density
    private val popupHeight = 52 * density

    private var rows = letterRows
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var symbolTyped = false
    private var enterIcon = EnterIcon.ENTER

    private val pointers = SparseArray<Pointer>()
    private var popup: AltPopup? = null
    private val handler = Handler(Looper.getMainLooper())
    private var longPressPointer = -1
    private val longPress = Runnable { onLongPress() }
    private var backspaceRepeated = false
    private val repeatBackspace = object : Runnable {
        override fun run() {
            backspaceRepeated = true
            listener?.onBackspace()
            handler.postDelayed(this, 50)
        }
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val path = Path()
    private val tmp = RectF()
    private val icons = HashMap<Int, Drawable>()

    val keyboardHeight get() = (topPadding + keysHeight + bottomPadding).toInt()

    // ---- stato pubblico ----

    fun setEnterIcon(icon: EnterIcon) {
        enterIcon = icon
        invalidate()
    }

    /** Maiuscola automatica a inizio frase: non tocca il blocco maiuscole. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.LOCKED) return
        shift = if (on) Shift.ON else Shift.OFF
        invalidate()
    }

    fun showLetters() = setRows(letterRows)
    fun showSymbols() = setRows(KeyboardLayouts.symbols)

    private fun setRows(newRows: List<KeyRow>) {
        rows = newRows
        symbolTyped = false
        layoutKeys()
        invalidate()
    }

    // ---- layout ----

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), keyboardHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        if (width == 0) return
        val unit = (width - 2 * sidePadding) / 10f
        val rowHeight = keysHeight / rows.size
        rows.forEachIndexed { r, row ->
            val top = topPadding + r * rowHeight
            var x = sidePadding + row.inset * unit
            row.keys.forEachIndexed { i, key ->
                val w = key.weight * unit
                key.rect.set(x + gapX, top + gapY, x + w - gapX, top + rowHeight - gapY)
                key.touch.set(
                    if (i == 0) 0f else x, top,
                    if (i == row.keys.lastIndex) width.toFloat() else x + w, top + rowHeight
                )
                x += w
                if (rows === letterRows && key.isChar && key.label.length == 1 && key.label[0] in 'a'..'z') {
                    letterX[key.label[0] - 'a'] = key.rect.centerX()
                    letterY[key.label[0] - 'a'] = key.rect.centerY()
                }
            }
        }
        keyUnit = unit
    }

    private fun findKey(x: Float, y: Float): Key? {
        val cy = y.coerceIn(topPadding, topPadding + keysHeight - 1)
        for (row in rows) for (key in row.keys) if (key.touch.contains(x, cy)) return key
        return null
    }

    // ---- disegno ----

    override fun onDraw(canvas: Canvas) {
        for (row in rows) for (key in row.keys) drawKey(canvas, key)
        val p = popup
        if (p != null) drawAltPopup(canvas, p) else drawPreview(canvas)
        drawTrail(canvas)
    }

    /** Scia della digitazione a scorrimento (l'ultimo tratto del tracciato). */
    private fun drawTrail(canvas: Canvas) {
        for (i in 0 until pointers.size()) {
            val pointer = pointers.valueAt(i)
            if (pointer.mode != Mode.GLIDE || pointer.xs.size < 2) continue
            val from = maxOf(0, pointer.xs.size - 48)
            path.rewind()
            path.moveTo(pointer.xs[from], pointer.ys[from])
            for (j in from + 1 until pointer.xs.size) path.lineTo(pointer.xs[j], pointer.ys[j])
            paint.color = theme.accent
            paint.alpha = 0xB0
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 7 * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.alpha = 0xFF
        }
    }

    private fun isPressed(key: Key): Boolean {
        for (i in 0 until pointers.size()) if (pointers.valueAt(i).key === key) return true
        return false
    }

    private fun displayLabel(text: String) =
        if (shift != Shift.OFF && rows === letterRows) text.uppercase() else text

    private fun drawKey(canvas: Canvas, key: Key) {
        val pressed = isPressed(key)
        val rect = key.rect
        val r = if (key.style == Key.STYLE_ACCENT) rect.height() / 2 else radius

        tmp.set(rect)
        tmp.offset(0f, density)
        paint.color = theme.keyShadow
        canvas.drawRoundRect(tmp, r, r, paint)

        paint.color = when (key.style) {
            Key.STYLE_ACCENT -> if (pressed) theme.accentPressed else theme.accent
            Key.STYLE_FUNCTIONAL -> if (pressed) theme.functionalKeyPressed else theme.functionalKey
            else -> if (pressed) theme.keyPressed else theme.key
        }
        canvas.drawRoundRect(rect, r, r, paint)

        val color = if (key.style == Key.STYLE_ACCENT) theme.onAccent else theme.text
        when (key.code) {
            Key.CODE_SHIFT -> drawShift(canvas, rect, color)
            Key.CODE_BACKSPACE -> drawIcon(canvas, R.drawable.ic_backspace, rect, color)
            Key.CODE_ENTER -> drawIcon(canvas, enterIcon.drawable, rect, color)
            Key.CODE_EMOJI -> drawSmiley(canvas, rect, color)
            Key.CODE_CHAR -> {
                if (key.label == " ") return
                textPaint.color = color
                textPaint.typeface = regular
                textPaint.textSize = (if (landscape) 19 else 22) * density
                drawCentered(canvas, displayLabel(key.label), rect.centerX(), rect.centerY())
                if (key.hint != null) {
                    textPaint.color = theme.hint
                    textPaint.textSize = 10 * density
                    canvas.drawText(key.hint, rect.right - 6 * density, rect.top + 12 * density, textPaint)
                }
            }
            else -> {
                textPaint.color = color
                textPaint.typeface = medium
                textPaint.textSize = 15 * density
                drawCentered(canvas, key.label, rect.centerX(), rect.centerY())
            }
        }
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        canvas.drawText(text, cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
    }

    private fun drawIcon(canvas: Canvas, id: Int, rect: RectF, color: Int) {
        val icon = icons.getOrPut(id) { context.getDrawable(id)!!.mutate() }
        val half = (11 * density).toInt()
        val cx = rect.centerX().toInt()
        val cy = rect.centerY().toInt()
        icon.setBounds(cx - half, cy - half, cx + half, cy + half)
        icon.setTint(color)
        icon.draw(canvas)
    }

    private fun drawShift(canvas: Canvas, rect: RectF, color: Int) {
        val u = density
        val cx = rect.centerX()
        val cy = rect.centerY() - (if (shift == Shift.LOCKED) 1.5f * u else 0f)
        path.rewind()
        path.moveTo(cx, cy - 8 * u)
        path.lineTo(cx - 8 * u, cy + 0.5f * u)
        path.lineTo(cx - 3.5f * u, cy + 0.5f * u)
        path.lineTo(cx - 3.5f * u, cy + 7 * u)
        path.lineTo(cx + 3.5f * u, cy + 7 * u)
        path.lineTo(cx + 3.5f * u, cy + 0.5f * u)
        path.lineTo(cx + 8 * u, cy + 0.5f * u)
        path.close()
        paint.color = color
        paint.strokeWidth = 1.8f * u
        paint.strokeJoin = Paint.Join.ROUND
        paint.style = if (shift == Shift.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        if (shift == Shift.LOCKED) {
            canvas.drawRect(cx - 4.4f * u, cy + 9.5f * u, cx + 4.4f * u, cy + 11.5f * u, paint)
        }
    }

    private fun drawSmiley(canvas: Canvas, rect: RectF, color: Int) {
        val u = density
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.7f * u
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, cy, 9 * u, paint)
        tmp.set(cx - 5 * u, cy - 4 * u, cx + 5 * u, cy + 5 * u)
        canvas.drawArc(tmp, 25f, 130f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - 3.2f * u, cy - 2.5f * u, 1.3f * u, paint)
        canvas.drawCircle(cx + 3.2f * u, cy - 2.5f * u, 1.3f * u, paint)
    }

    private fun drawBubble(canvas: Canvas, rect: RectF) {
        paint.color = theme.popup
        paint.setShadowLayer(6 * density, 0f, 2 * density, 0x55000000)
        canvas.drawRoundRect(rect, 10 * density, 10 * density, paint)
        paint.clearShadowLayer()
    }

    /** Anteprima del tasto premuto, sopra il tasto. */
    private fun drawPreview(canvas: Canvas) {
        if (!previewEnabled) return
        for (i in 0 until pointers.size()) {
            val pointer = pointers.valueAt(i)
            val key = pointer.key ?: continue
            if (pointer.consumed || !key.isChar || key.label == " ") continue
            val rect = bubbleRect(key, key.rect.width() + 10 * density)
            drawBubble(canvas, rect)
            textPaint.color = theme.text
            textPaint.typeface = regular
            textPaint.textSize = 28 * density
            drawCentered(canvas, displayLabel(key.label), rect.centerX(), rect.centerY())
        }
    }

    private fun bubbleRect(key: Key, width: Float): RectF {
        val margin = 2 * density
        val left = (key.rect.centerX() - width / 2).coerceIn(margin, maxOf(margin, this.width - width - margin))
        val top = (key.rect.top - popupHeight - 4 * density).coerceAtLeast(-overdrawTop + margin)
        return RectF(left, top, left + width, top + popupHeight)
    }

    private fun drawAltPopup(canvas: Canvas, p: AltPopup) {
        drawBubble(canvas, p.rect)
        val cell = p.rect.width() / p.items.size
        textPaint.typeface = regular
        textPaint.textSize = 24 * density
        p.items.forEachIndexed { i, item ->
            val left = p.rect.left + i * cell
            if (i == p.selected) {
                tmp.set(left + 3 * density, p.rect.top + 3 * density, left + cell - 3 * density, p.rect.bottom - 3 * density)
                paint.color = theme.accent
                canvas.drawRoundRect(tmp, 8 * density, 8 * density, paint)
            }
            textPaint.color = if (i == p.selected) theme.onAccent else theme.text
            drawCentered(canvas, item, left + cell / 2, p.rect.centerY())
        }
    }

    // ---- tocco ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                // Durante uno scorrimento (parola, cursore, cancellazione) gli altri tocchi vengono ignorati.
                for (i in 0 until pointers.size()) if (pointers.valueAt(i).mode != Mode.KEY) return true
                // Digitazione veloce: un secondo dito conferma subito il tasto ancora premuto dal primo.
                for (i in 0 until pointers.size()) release(pointers.keyAt(i), pointers.valueAt(i))
                val x = event.getX(index)
                val y = event.getY(index)
                val pointer = Pointer(findKey(x, y), x, y)
                pointers.put(event.getPointerId(index), pointer)
                press(event.getPointerId(index), pointer)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val pointer = pointers.get(id) ?: continue
                val x = event.getX(i)
                val y = event.getY(i)
                val p = popup
                if (p != null && p.pointerId == id) {
                    val cell = p.rect.width() / p.items.size
                    p.selected = ((x - p.rect.left) / cell).toInt().coerceIn(0, p.items.lastIndex)
                    continue
                }
                if (pointer.mode == Mode.KEY) startSwipe(pointer, x, y)
                when (pointer.mode) {
                    Mode.GLIDE -> {
                        for (h in 0 until event.historySize) {
                            pointer.xs.add(event.getHistoricalX(i, h))
                            pointer.ys.add(event.getHistoricalY(i, h))
                        }
                        pointer.xs.add(x)
                        pointer.ys.add(y)
                    }
                    Mode.CURSOR -> {
                        val step = 9 * density
                        while (abs(x - pointer.stepX) >= step) {
                            val direction = if (x > pointer.stepX) 1 else -1
                            pointer.stepX += direction * step
                            listener?.onCursorMove(direction)
                        }
                    }
                    Mode.DELETE -> {
                        // Più si scorre a sinistra più parole si selezionano; tornando indietro si deselezionano.
                        val distance = pointer.downX - x
                        val words = if (distance < keyUnit * 0.3f) 0
                        else 1 + ((distance - keyUnit * 0.6f) / (keyUnit * 0.9f)).toInt().coerceAtLeast(0)
                        if (words != pointer.words) {
                            pointer.words = words
                            vibrate()
                            listener?.onDeleteSelect(words)
                        }
                    }
                    Mode.KEY -> if (!pointer.consumed || pointer.key?.code == Key.CODE_BACKSPACE) {
                        val key = findKey(x, y)
                        if (key !== pointer.key) {
                            handler.removeCallbacks(repeatBackspace)
                            pointer.key = key
                            pointer.consumed = key?.isChar != true
                            startLongPress(id, key)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                pointers.get(id)?.let { release(id, it) }
                pointers.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until pointers.size()) if (pointers.valueAt(i).mode == Mode.DELETE) {
                    listener?.onDeleteSelect(0)
                    listener?.onDeleteCommit()
                }
                pointers.clear()
                popup = null
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeatBackspace)
            }
        }
        invalidate()
        return true
    }

    /** Riconosce l'inizio dei gesti di scorrimento come su Gboard: parola, cursore sulla barra spaziatrice, cancella parola. */
    private fun startSwipe(pointer: Pointer, x: Float, y: Float) {
        val start = pointer.startKey ?: return
        val dx = x - pointer.downX
        when {
            start.code == Key.CODE_BACKSPACE -> if (-dx > keyUnit * 0.6f) {
                pointer.mode = Mode.DELETE
                handler.removeCallbacks(repeatBackspace)
            }
            pointer.consumed || !start.isChar -> return
            start.label == " " -> if (abs(dx) > 14 * density) {
                pointer.mode = Mode.CURSOR
                pointer.stepX = x
            }
            glideEnabled && rows === letterRows && pointers.size() == 1 && start.label[0].isLetter() ->
                if (hypot(dx, y - pointer.downY) > keyUnit * 0.8f) {
                    pointer.mode = Mode.GLIDE
                    pointer.key = null
                    pointer.xs.add(pointer.downX)
                    pointer.ys.add(pointer.downY)
                }
        }
        if (pointer.mode != Mode.KEY) {
            pointer.consumed = true
            handler.removeCallbacks(longPress)
        }
    }

    @Suppress("DEPRECATION")
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private fun vibrate() {
        if (!vibrationEnabled) return
        try {
            vibrator?.vibrate(
                if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // Dispositivo senza vibrazione.
        }
    }

    private fun press(id: Int, pointer: Pointer) {
        val key = pointer.key ?: return
        vibrate()
        when (key.code) {
            Key.CODE_SHIFT -> {
                pointer.consumed = true
                val now = SystemClock.uptimeMillis()
                shift = when {
                    shift == Shift.LOCKED -> Shift.OFF
                    now - lastShiftTap < ViewConfiguration.getDoubleTapTimeout() -> Shift.LOCKED
                    shift == Shift.ON -> Shift.OFF
                    else -> Shift.ON
                }
                lastShiftTap = now
            }
            Key.CODE_BACKSPACE -> {
                // Il tocco semplice cancella al rilascio, così iniziare uno scorrimento non cancella nulla.
                pointer.consumed = true
                backspaceRepeated = false
                handler.postDelayed(repeatBackspace, 400)
            }
            else -> startLongPress(id, key)
        }
    }

    private fun startLongPress(id: Int, key: Key?) {
        handler.removeCallbacks(longPress)
        if (key != null && key.isChar && (key.alternates.isNotEmpty() || key.label == " ")) {
            longPressPointer = id
            handler.postDelayed(longPress, 300)
        }
    }

    private fun onLongPress() {
        val pointer = pointers.get(longPressPointer) ?: return
        val key = pointer.key ?: return
        if (pointer.consumed) return
        vibrate()
        if (key.label == " ") {
            pointer.consumed = true
            listener?.onSpaceLongPress()
        } else {
            val items = key.alternates.map(::displayLabel)
            val cell = maxOf(key.rect.width() + 2 * gapX, 34 * density)
            popup = AltPopup(longPressPointer, items, bubbleRect(key, cell * items.size), 0)
        }
        invalidate()
    }

    /** Fine del tocco: conferma il tasto (o l'alternativa scelta nel popup). */
    private fun release(id: Int, pointer: Pointer) {
        val p = popup
        if (p != null && p.pointerId == id) {
            popup = null
            pointer.consumed = true
            commit(p.items[p.selected])
            return
        }
        if (id == longPressPointer) handler.removeCallbacks(longPress)
        if (pointer.mode == Mode.GLIDE) {
            pointer.mode = Mode.KEY
            val caps = shift.ordinal
            if (shift == Shift.ON) shift = Shift.OFF
            listener?.onGesture(pointer.xs.toFloatArray(), pointer.ys.toFloatArray(), caps)
            return
        }
        if (pointer.mode == Mode.DELETE) {
            pointer.mode = Mode.KEY
            pointer.key = null
            listener?.onDeleteCommit()
            return
        }
        val key = pointer.key
        if (key?.code == Key.CODE_BACKSPACE) {
            handler.removeCallbacks(repeatBackspace)
            if (!backspaceRepeated && !pointer.released) listener?.onBackspace()
            pointer.released = true
        }
        if (pointer.consumed || key == null) return
        pointer.consumed = true
        when (key.code) {
            Key.CODE_SYMBOLS -> setRows(KeyboardLayouts.symbols)
            Key.CODE_SYMBOLS_MORE -> setRows(KeyboardLayouts.symbolsMore)
            Key.CODE_LETTERS -> setRows(letterRows)
            Key.CODE_ENTER -> listener?.onEnter()
            Key.CODE_EMOJI -> listener?.onEmoji()
            Key.CODE_CHAR -> commit(displayLabel(key.label))
        }
    }

    private fun commit(text: String) {
        val onLetters = rows === letterRows
        if (shift == Shift.ON) shift = Shift.OFF
        // Come Gboard: dopo un simbolo, lo spazio riporta alle lettere.
        if (!onLetters && text == " " && symbolTyped) setRows(letterRows)
        else if (!onLetters) symbolTyped = true
        listener?.onText(text)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        pointers.clear()
        popup = null
    }
}
