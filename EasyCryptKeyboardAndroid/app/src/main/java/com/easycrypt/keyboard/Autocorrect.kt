package com.easycrypt.keyboard

import android.content.Context
import android.content.SharedPreferences
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.ln

/**
 * Suggerimenti, correzione automatica e digitazione a scorrimento in stile Gboard, basati su un elenco di
 * parole italiane ordinate per frequenza (assets/words_it.txt, una parola per riga, la più frequente per prima).
 * Tutto avviene sul dispositivo: le parole digitate non escono dalla tastiera.
 */
class Autocorrect private constructor() {

    /**
     * Suggerimenti per la parola in corso. [correction] è la parola che verrà sostituita automaticamente
     * alla pressione dello spazio (null se la parola va lasciata com'è).
     */
    class Suggestions(val typed: String, val items: List<String>, val correction: String?)

    private class Dictionary(
        /** Parole in ordine di frequenza e la loro forma senza accenti. */
        val words: Array<String>,
        val keys: Array<String>,
        val ranks: HashMap<String, Int>,
        /** Le stesse parole ordinate alfabeticamente senza accenti, per cercare per prefisso. */
        val sortedKeys: Array<String>,
        val sortedWords: Array<String>,
        /** Indici delle parole (di almeno due lettere) per lettera iniziale, per la digitazione a scorrimento. */
        val byFirstLetter: Array<IntArray>,
    )

    @Volatile
    private var dictionary: Dictionary? = null
    private val ignored = HashSet<String>()

    // Parole imparate: quelle che l'utente ha scelto di tenere così come le ha scritte.
    private var prefs: SharedPreferences? = null
    private var baseWords: List<String> = emptyList()
    private val learned = LinkedHashSet<String>()
    private var last: Suggestions? = null

    constructor(context: Context) : this() {
        val assets = context.applicationContext.assets
        val prefs = context.applicationContext.getSharedPreferences("easycrypt_settings", Context.MODE_PRIVATE)
        this.prefs = prefs
        learned.addAll(prefs.getStringSet(LEARNED_WORDS, emptySet()).orEmpty())
        val extra = learned.toList()
        thread(name = "dictionary") {
            val words = ArrayList<String>(50000)
            try {
                assets.open(ASSET).bufferedReader().useLines { lines ->
                    for (line in lines) if (line.isNotEmpty()) words.add(line)
                }
            } catch (e: Exception) {
                // Dizionario assente: suggerimenti e correzione restano inattivi.
            }
            baseWords = words
            dictionary = build(words, extra)
        }
    }

    /** Dizionario già in memoria (usato dai test). */
    constructor(words: List<String>) : this() {
        dictionary = build(words, emptyList())
    }

    private fun build(list: List<String>, extra: List<String>): Dictionary {
        val all = if (extra.isEmpty()) list else list.take(LEARNED_RANK) + extra + list.drop(LEARNED_RANK)
        val words = all.distinct().toTypedArray()
        val keys = Array(words.size) { stripAccents(words[it]) }
        val ranks = HashMap<String, Int>(words.size * 2)
        words.forEachIndexed { i, word -> ranks[word] = i }
        val order = words.indices.sortedBy { keys[it] }
        val byFirst = Array(26) { ArrayList<Int>() }
        keys.forEachIndexed { i, key ->
            if (key.length >= 2 && key.all { it in 'a'..'z' }) byFirst[key[0] - 'a'].add(i)
        }
        return Dictionary(
            words, keys, ranks,
            Array(order.size) { keys[order[it]] },
            Array(order.size) { words[order[it]] },
            Array(26) { byFirst[it].toIntArray() },
        )
    }

    /**
     * L'utente ha scelto di tenere questa parola: non va più corretta e viene imparata, così compare
     * anche tra i suggerimenti e nella digitazione a scorrimento.
     */
    fun ignore(word: String) {
        val lower = word.lowercase()
        ignored.add(lower)
        last = null
        if (lower.length < 2 || !lower.all(Char::isLetter) || !learned.add(lower)) return
        prefs?.edit()?.putStringSet(LEARNED_WORDS, HashSet(learned))?.apply()
        val base = baseWords
        val extra = learned.toList()
        if (base.isNotEmpty()) thread(name = "dictionary") { dictionary = build(base, extra) }
    }

    /** Parola da sostituire a [word] alla pressione dello spazio, oppure null. */
    fun correct(word: String): String? = suggest(word)?.correction

    /** Fino a tre suggerimenti per la parola in corso: al centro (indice 1) quello principale. */
    fun suggest(word: String): Suggestions? {
        last?.let { if (it.typed == word) return it }
        val dict = dictionary ?: return null
        val lower = word.lowercase()
        if (lower.isEmpty() || !lower.all(Char::isLetter)) return null

        val known = lower in dict.ranks || lower in ignored
        val correction = if (known || lower.length < 2) null else bestEdit(dict, lower)
        val completions = completions(dict, lower, 3)

        // Come Gboard: al centro la correzione (o la parola scritta se è valida), a sinistra la parola
        // scritta quando sta per essere corretta, il resto sono completamenti.
        val center = correction ?: if (known) lower else completions.firstOrNull() ?: lower
        val others = ArrayList<String>(3)
        if (center != lower) others.add(lower)
        for (c in completions) if (c != center && c !in others) others.add(c)
        val items = listOf(others.getOrNull(0), center, others.getOrNull(1)).map { it?.let { s -> applyCase(word, s) } ?: "" }
        return Suggestions(word, items, correction?.let { applyCase(word, it) }).also { last = it }
    }

    /** Parola a distanza di una modifica; si preferisce chi differisce solo per gli accenti (perche -> perché). */
    private fun bestEdit(dict: Dictionary, lower: String): String? {
        val plainForm = stripAccents(lower)
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        val buffer = StringBuilder(lower.length + 1)
        fun consider() {
            val candidate = buffer.toString()
            val rank = dict.ranks[candidate] ?: return
            val score = if (stripAccents(candidate) == plainForm) rank - ACCENT_BONUS else rank
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        for (i in 0..lower.length) {
            if (i < lower.length) {
                buffer.setLength(0)
                buffer.append(lower, 0, i).append(lower, i + 1, lower.length)
                consider()
            }
            if (i < lower.length - 1) {
                buffer.setLength(0)
                buffer.append(lower, 0, i).append(lower[i + 1]).append(lower[i]).append(lower, i + 2, lower.length)
                consider()
            }
            for (c in ALPHABET) {
                if (i < lower.length) {
                    buffer.setLength(0)
                    buffer.append(lower, 0, i).append(c).append(lower, i + 1, lower.length)
                    consider()
                }
                buffer.setLength(0)
                buffer.append(lower, 0, i).append(c).append(lower, i, lower.length)
                consider()
            }
        }
        return best
    }

    /** Le parole più frequenti che iniziano con [lower] (senza badare agli accenti), esclusa la parola stessa. */
    private fun completions(dict: Dictionary, lower: String, limit: Int): List<String> {
        val prefix = stripAccents(lower)
        var low = 0
        var high = dict.sortedKeys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (dict.sortedKeys[mid] < prefix) low = mid + 1 else high = mid
        }
        val best = ArrayList<String>(limit + 1)
        val bestRanks = ArrayList<Int>(limit + 1)
        var i = low
        while (i < dict.sortedKeys.size && dict.sortedKeys[i].startsWith(prefix)) {
            val word = dict.sortedWords[i++]
            if (word == lower) continue
            val rank = dict.ranks[word] ?: continue
            if (best.size == limit && rank > bestRanks[limit - 1]) continue
            var at = best.size
            while (at > 0 && bestRanks[at - 1] > rank) at--
            best.add(at, word)
            bestRanks.add(at, rank)
            if (best.size > limit) {
                best.removeAt(limit)
                bestRanks.removeAt(limit)
            }
        }
        return best
    }

    // ---- digitazione a scorrimento ----

    /**
     * Riconosce la parola tracciata scorrendo il dito sulla tastiera. Per ogni parola che inizia e finisce
     * vicino agli estremi del tracciato si sommano tre misure:
     * - quanto tracciato e percorso ideale (da un tasto all'altro) restano vicini percorrendoli insieme;
     * - quanto il tracciato si allontana dal percorso ideale della parola (lettere in più visitate dal dito);
     * - quanto la parola è rara.
     * [keyX]/[keyY] sono i centri dei tasti da 'a' a 'z'. Restituisce le parole più probabili, la migliore per prima.
     */
    fun decodeGesture(xs: FloatArray, ys: FloatArray, keyX: FloatArray, keyY: FloatArray, keyWidth: Float): List<String> {
        val dict = dictionary ?: return emptyList()
        if (xs.size < 2) return emptyList()
        val path = FloatArray(GESTURE_SAMPLES * 2)
        resample(xs, ys, xs.size, path)
        val pathLength = length(xs, ys, xs.size)
        val endX = xs[xs.size - 1]
        val endY = ys[ys.size - 1]

        val bestWords = ArrayList<String>(GESTURE_RESULTS + 1)
        val bestScores = ArrayList<Float>(GESTURE_RESULTS + 1)
        val idealX = FloatArray(MAX_GESTURE_WORD)
        val idealY = FloatArray(MAX_GESTURE_WORD)
        val ideal = FloatArray(GESTURE_SAMPLES * 2)

        for (first in 0 until 26) {
            val startDistance = hypot(keyX[first] - xs[0], keyY[first] - ys[0])
            if (startDistance > keyWidth * ENDPOINT_TOLERANCE) continue
            for (index in dict.byFirstLetter[first]) {
                val key = dict.keys[index]
                if (key.length > MAX_GESTURE_WORD) continue
                val lastLetter = key[key.length - 1] - 'a'
                if (hypot(keyX[lastLetter] - endX, keyY[lastLetter] - endY) > keyWidth * ENDPOINT_TOLERANCE) continue

                // Percorso ideale: i centri dei tasti, senza ripetere le lettere doppie.
                var count = 0
                for (c in key) {
                    val letter = c - 'a'
                    if (count > 0 && idealX[count - 1] == keyX[letter] && idealY[count - 1] == keyY[letter]) continue
                    idealX[count] = keyX[letter]
                    idealY[count] = keyY[letter]
                    count++
                }
                // Un dito taglia le curve: il tracciato è spesso più corto del percorso ideale, raramente più lungo.
                val idealLength = length(idealX, idealY, count)
                if (idealLength > pathLength * 1.7f + keyWidth * 2f || idealLength < pathLength * 0.6f - keyWidth) continue

                val frequency = FREQUENCY_WEIGHT * ln(index + 2f)
                val worst = if (bestWords.size == GESTURE_RESULTS) bestScores[GESTURE_RESULTS - 1] else Float.MAX_VALUE
                if (frequency >= worst) continue

                // 1) Tracciato e percorso ideale, percorsi alla stessa andatura, devono restare vicini.
                resample(idealX, idealY, count, ideal)
                var apart = 0f
                for (j in 0 until GESTURE_SAMPLES) {
                    apart += hypot(ideal[2 * j] - path[2 * j], ideal[2 * j + 1] - path[2 * j + 1])
                }
                var score = frequency + ALIGN_WEIGHT * apart / GESTURE_SAMPLES / keyWidth
                if (score >= worst) continue

                // 2) Ogni punto del tracciato deve stare vicino al percorso ideale della parola
                //    (un dito taglia le curve, ma non passa su lettere che non c'entrano).
                var outside = 0f
                for (j in 0 until GESTURE_SAMPLES) {
                    val px = path[2 * j]
                    val py = path[2 * j + 1]
                    var nearest = hypot(idealX[0] - px, idealY[0] - py)
                    for (i in 1 until count) {
                        val d = segmentDistance(px, py, idealX[i - 1], idealY[i - 1], idealX[i], idealY[i])
                        if (d < nearest) nearest = d
                    }
                    outside += nearest
                }
                score += SHAPE_WEIGHT * outside / GESTURE_SAMPLES / keyWidth
                if (score >= worst) continue

                var at = bestWords.size
                while (at > 0 && bestScores[at - 1] > score) at--
                bestWords.add(at, dict.words[index])
                bestScores.add(at, score)
                if (bestWords.size > GESTURE_RESULTS) {
                    bestWords.removeAt(GESTURE_RESULTS)
                    bestScores.removeAt(GESTURE_RESULTS)
                }
            }
        }
        return bestWords
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared > 0f) (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f) else 0f
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    private fun length(xs: FloatArray, ys: FloatArray, count: Int): Float {
        var total = 0f
        for (i in 1 until count) total += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
        return total
    }

    /** Ricampiona una spezzata in GESTURE_SAMPLES punti equidistanti (x0, y0, x1, y1, ...). */
    private fun resample(xs: FloatArray, ys: FloatArray, count: Int, out: FloatArray) {
        val step = length(xs, ys, count) / (GESTURE_SAMPLES - 1)
        var segment = 1
        var walked = 0f
        for (i in 0 until GESTURE_SAMPLES) {
            val target = step * i
            while (segment < count - 1 && walked + hypot(xs[segment] - xs[segment - 1], ys[segment] - ys[segment - 1]) < target) {
                walked += hypot(xs[segment] - xs[segment - 1], ys[segment] - ys[segment - 1])
                segment++
            }
            val previous = if (count > 1) segment - 1 else 0
            val current = if (count > 1) segment else 0
            val segmentLength = hypot(xs[current] - xs[previous], ys[current] - ys[previous])
            val t = if (segmentLength > 0f) ((target - walked) / segmentLength).coerceIn(0f, 1f) else 0f
            out[2 * i] = xs[previous] + (xs[current] - xs[previous]) * t
            out[2 * i + 1] = ys[previous] + (ys[current] - ys[previous]) * t
        }
    }

    private fun applyCase(typed: String, word: String) = when {
        typed.length > 1 && typed.all(Char::isUpperCase) -> word.uppercase()
        typed[0].isUpperCase() -> word.replaceFirstChar(Char::uppercase)
        else -> word
    }

    private fun stripAccents(text: String): String {
        var plain = true
        for (c in text) if (c > 'z') {
            plain = false
            break
        }
        if (plain) return text
        val chars = CharArray(text.length) { i ->
            when (text[i]) {
                'à', 'á', 'â', 'ä', 'ã', 'å' -> 'a'
                'è', 'é', 'ê', 'ë' -> 'e'
                'ì', 'í', 'î', 'ï' -> 'i'
                'ò', 'ó', 'ô', 'ö', 'õ' -> 'o'
                'ù', 'ú', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'ñ' -> 'n'
                else -> text[i]
            }
        }
        return String(chars)
    }

    private companion object {
        const val ASSET = "words_it.txt"
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyzàèéìòù"
        const val ACCENT_BONUS = 1_000_000
        const val GESTURE_SAMPLES = 32
        const val GESTURE_RESULTS = 3
        const val MAX_GESTURE_WORD = 24
        const val ENDPOINT_TOLERANCE = 1.4f

        // Pesi regolati misurando la precisione su tracciati simulati (GestureTest.accuracyOnSloppySwipes).
        const val FREQUENCY_WEIGHT = 0.05f
        const val SHAPE_WEIGHT = 2f
        const val ALIGN_WEIGHT = 0.5f

        /** Posizione in classifica data alle parole imparate dall'utente. */
        const val LEARNED_RANK = 3000
        const val LEARNED_WORDS = "learned_words"
    }
}
