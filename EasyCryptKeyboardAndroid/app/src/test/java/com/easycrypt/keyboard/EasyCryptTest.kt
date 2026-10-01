package com.easycrypt.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EasyCryptTest {
    // Vettori generati con encryptText() di easy-crypt-fe/src/utils/crypto.js
    private val webVector = "8KLLgNkK0VXGB2IVq4w4EBOS/VsUveh82aK9gqGZrRAMSHffmP0a2CvI9BB7TtiVgS3xD2VYqYO1AD6l8qGtvwjfb8zQbhU="
    private val webVectorUnicodePassword = "NHNplYLyJlf2JCPTbvO2xHACNefAIEu6XbxV+dNS6g4OB/8vGrS3BNcyYJLZST7fnuljzRBEMbd6pts="

    @Test
    fun decryptsWebAppOutput() {
        assertEquals("Ciao mondo! àèìòù 🔐", EasyCrypt.decrypt(webVector, "password123"))
        assertEquals("test unicode pw", EasyCrypt.decrypt(webVectorUnicodePassword, "pässwörd€"))
    }

    @Test
    fun roundTrip() {
        val encrypted = EasyCrypt.encrypt("messaggio segreto\nsu due righe", "pw")
        assertTrue(EasyCrypt.looksEncrypted(encrypted))
        assertEquals("messaggio segreto\nsu due righe", EasyCrypt.decrypt(encrypted, "pw"))
        // usato per la verifica inversa con la web app
        System.getProperty("easycrypt.out")?.let { File(it).writeText(EasyCrypt.encrypt("Da Android 🔐 àè", "pässwörd€")) }
    }

    @Test
    fun sessionKeyReuseGivesDistinctValidOutputs() {
        val session = EasyCrypt.newSessionKey("pw")
        val a = EasyCrypt.encrypt("ciao", session)
        val b = EasyCrypt.encrypt("ciao", session)
        assertNotEquals(a, b)
        assertEquals("ciao", EasyCrypt.decrypt(a, "pw"))
        assertEquals("ciao", EasyCrypt.decrypt(b, "pw"))
    }

    @Test
    fun wrongPasswordFails() {
        assertThrows(Exception::class.java) { EasyCrypt.decrypt(webVector, "sbagliata") }
        assertThrows(Exception::class.java) { EasyCrypt.decrypt("non è base64", "pw") }
        assertFalse(EasyCrypt.looksEncrypted("ciao come stai"))
    }
}
