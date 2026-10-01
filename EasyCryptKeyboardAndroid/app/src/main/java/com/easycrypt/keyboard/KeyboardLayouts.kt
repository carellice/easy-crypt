package com.easycrypt.keyboard

import android.graphics.RectF

class Key(
    val code: Int,
    val label: String = "",
    val weight: Float = 1f,
    val hint: String? = null,
    val alternates: List<String> = emptyList(),
    val style: Int = STYLE_LETTER,
) {
    /** Area disegnata del tasto. */
    val rect = RectF()

    /** Area sensibile al tocco (include gli spazi tra i tasti). */
    val touch = RectF()

    val isChar get() = code == CODE_CHAR

    companion object {
        const val CODE_CHAR = 0
        const val CODE_SHIFT = -1
        const val CODE_BACKSPACE = -2
        const val CODE_SYMBOLS = -3
        const val CODE_LETTERS = -4
        const val CODE_SYMBOLS_MORE = -5
        const val CODE_ENTER = -6
        const val CODE_EMOJI = -7

        const val STYLE_LETTER = 0
        const val STYLE_FUNCTIONAL = 1
        const val STYLE_ACCENT = 2
    }
}

class KeyRow(val keys: List<Key>, val inset: Float = 0f)

object KeyboardLayouts {
    private val accents = mapOf(
        "a" to "àáâäæãåª", "e" to "èéêëęė€", "i" to "ìíîïįī", "o" to "òóôöõœøº", "u" to "ùúûüū",
        "c" to "çćč", "n" to "ñń", "s" to "ßśš", "y" to "ýÿ", "z" to "žźż", "l" to "ł",
    )

    private fun letters(chars: String, hints: String? = null) = chars.mapIndexed { i, c ->
        val hint = hints?.get(i)?.toString()
        val alts = accents[c.toString()].orEmpty().map(Char::toString) + listOfNotNull(hint)
        Key(Key.CODE_CHAR, c.toString(), hint = hint, alternates = alts)
    }

    private fun symbols(vararg labels: String) = labels.map { Key(Key.CODE_CHAR, it) }

    private fun functional(code: Int, label: String = "", weight: Float = 1.5f) =
        Key(code, label, weight, style = Key.STYLE_FUNCTIONAL)

    private fun punctuation(label: String, alternates: String = "") = Key(
        Key.CODE_CHAR, label, alternates = alternates.map(Char::toString), style = Key.STYLE_FUNCTIONAL
    )

    private fun backspace() = functional(Key.CODE_BACKSPACE)
    private fun space(weight: Float = 4f) = Key(Key.CODE_CHAR, " ", weight)
    private fun enter() = Key(Key.CODE_ENTER, weight = 1.5f, style = Key.STYLE_ACCENT)

    private fun bottomRow(switchCode: Int, switchLabel: String) = KeyRow(
        listOf(
            functional(switchCode, switchLabel),
            punctuation(","),
            functional(Key.CODE_EMOJI, weight = 1f),
            space(),
            punctuation(".", "!?;:'\"-()@&"),
            enter(),
        )
    )

    val letters = listOf(
        KeyRow(letters("qwertyuiop", "1234567890")),
        KeyRow(letters("asdfghjkl"), inset = 0.5f),
        KeyRow(listOf(functional(Key.CODE_SHIFT)) + letters("zxcvbnm") + backspace()),
        bottomRow(Key.CODE_SYMBOLS, "?123"),
    )

    /** Lettere con la riga fissa dei numeri in alto (senza i numeri in piccolo sulla prima riga di lettere). */
    val lettersWithNumbers = listOf(
        KeyRow(symbols("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
        KeyRow(letters("qwertyuiop")),
        KeyRow(letters("asdfghjkl"), inset = 0.5f),
        KeyRow(listOf(functional(Key.CODE_SHIFT)) + letters("zxcvbnm") + backspace()),
        bottomRow(Key.CODE_SYMBOLS, "?123"),
    )

    val symbols = listOf(
        KeyRow(symbols("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")),
        KeyRow(symbols("@", "#", "€", "_", "&", "-", "+", "(", ")", "/")),
        KeyRow(
            listOf(functional(Key.CODE_SYMBOLS_MORE, "=\\<")) +
                symbols("*", "\"", "'", ":", ";", "!", "?") + backspace()
        ),
        bottomRow(Key.CODE_LETTERS, "ABC"),
    )

    val symbolsMore = listOf(
        KeyRow(symbols("~", "`", "|", "•", "√", "π", "÷", "×", "§", "∆")),
        KeyRow(symbols("£", "¢", "$", "¥", "^", "°", "=", "{", "}", "\\")),
        KeyRow(
            listOf(functional(Key.CODE_SYMBOLS, "?123")) +
                symbols("%", "©", "®", "™", "✓", "[", "]") + backspace()
        ),
        KeyRow(
            listOf(
                functional(Key.CODE_LETTERS, "ABC"),
                punctuation("<"),
                space(5f),
                punctuation(">"),
                enter(),
            )
        ),
    )
}
