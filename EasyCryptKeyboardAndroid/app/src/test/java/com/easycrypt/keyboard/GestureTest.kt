package com.easycrypt.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.hypot

class GestureTest {
    private val words = File("src/main/assets/words_it.txt").readLines().filter { it.isNotEmpty() }
    private val autocorrect = Autocorrect(words)

    // Centri dei tasti di una QWERTY larga 1000 con righe alte 150 (tasto = 100).
    private val keyX = FloatArray(26)
    private val keyY = FloatArray(26)

    init {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 50f, "zxcvbnm" to 150f).forEachIndexed { row, (letters, inset) ->
            letters.forEachIndexed { i, c ->
                keyX[c - 'a'] = inset + i * 100f + 50f
                keyY[c - 'a'] = row * 150f + 75f
            }
        }
    }

    private fun plain(word: String) = word
        .replace('à', 'a').replace('è', 'e').replace('é', 'e').replace('ì', 'i').replace('ò', 'o').replace('ù', 'u')

    /**
     * Simula un dito vero: passa vicino (non sopra) a ogni lettera, taglia le curve e traccia una linea continua.
     * [noise] è l'imprecisione su ogni tasto, [smoothing] quanto vengono tagliate le curve (in unità di tastiera).
     */
    private fun swipe(word: String, noise: Float, smoothing: Float, seed: Long): List<String> {
        val random = Random(seed)
        val targetsX = ArrayList<Float>()
        val targetsY = ArrayList<Float>()
        for (c in plain(word)) {
            targetsX.add(keyX[c - 'a'] + random.nextGaussian().toFloat() * noise)
            targetsY.add(keyY[c - 'a'] + random.nextGaussian().toFloat() * noise)
        }
        // Linea densa tra un tasto e l'altro.
        val denseX = arrayListOf(targetsX[0])
        val denseY = arrayListOf(targetsY[0])
        for (i in 1 until targetsX.size) {
            val steps = maxOf(1, (hypot(targetsX[i] - targetsX[i - 1], targetsY[i] - targetsY[i - 1]) / 12f).toInt())
            for (s in 1..steps) {
                denseX.add(targetsX[i - 1] + (targetsX[i] - targetsX[i - 1]) * s / steps)
                denseY.add(targetsY[i - 1] + (targetsY[i] - targetsY[i - 1]) * s / steps)
            }
        }
        // Media mobile: arrotonda gli angoli come fa un dito che non si ferma sulle lettere.
        val window = (smoothing / 12f).toInt()
        val xs = FloatArray(denseX.size)
        val ys = FloatArray(denseX.size)
        for (i in denseX.indices) {
            val from = maxOf(0, i - window)
            val to = minOf(denseX.lastIndex, i + window)
            var sx = 0f
            var sy = 0f
            for (j in from..to) {
                sx += denseX[j]
                sy += denseY[j]
            }
            xs[i] = sx / (to - from + 1)
            ys[i] = sy / (to - from + 1)
        }
        return autocorrect.decodeGesture(xs, ys, keyX, keyY, 100f)
    }

    @Test
    fun decodesCommonWords() {
        val common = listOf(
            "ciao", "come", "questo", "grazie", "casa", "sono", "bene", "quando", "domani", "anche",
            "tutto", "fare", "molto", "sempre", "amore", "lavoro", "tempo", "oggi", "buongiorno", "stasera",
        )
        var exact = 0
        for ((n, word) in common.withIndex()) {
            val result = swipe(word, noise = 20f, smoothing = 40f, seed = n.toLong())
            assertTrue("$word non trovata: $result", word in result)
            if (result.first() == word) exact++
        }
        assertTrue("solo $exact su ${common.size} al primo posto", exact >= common.size * 8 / 10)
    }

    @Test
    fun accentedWordsAreReachable() {
        assertEquals("perché", swipe("perche", noise = 8f, smoothing = 20f, seed = 1).first())
        assertEquals("città", swipe("cita", noise = 8f, smoothing = 20f, seed = 2).first())
    }

    /** Misura la precisione su molte parole con tracciati imprecisi; il risultato finisce in build/gesture_accuracy.txt. */
    @Test
    fun accuracyOnSloppySwipes() {
        val sample = words.take(6000).filter { w -> w.length >= 2 && plain(w).all { it in 'a'..'z' } }
            .filterIndexed { i, _ -> i % 4 == 0 }
        val report = StringBuilder()
        var worstTop3 = 1.0
        for ((noise, smoothing) in listOf(15f to 30f, 30f to 60f, 40f to 90f)) {
            var top1 = 0
            var top3 = 0
            val misses = ArrayList<String>()
            for ((n, word) in sample.withIndex()) {
                val result = swipe(word, noise, smoothing, seed = n.toLong())
                // Parole con lo stesso identico tracciato (casa/cassa) contano come trovate se compaiono.
                if (result.firstOrNull() == word) top1++
                if (word in result) top3++ else if (misses.size < 25) misses.add("$word->$result")
            }
            val line = "noise=$noise smoothing=$smoothing  top1=${100 * top1 / sample.size}%  top3=${100 * top3 / sample.size}%  (${sample.size} parole)"
            report.appendLine(line).appendLine("  mancate: $misses")
            worstTop3 = minOf(worstTop3, top3.toDouble() / sample.size)
        }
        File("build").mkdirs()
        File("build/gesture_accuracy.txt").writeText(report.toString())
        assertTrue("precisione troppo bassa:\n$report", worstTop3 > 0.75)
    }
}
