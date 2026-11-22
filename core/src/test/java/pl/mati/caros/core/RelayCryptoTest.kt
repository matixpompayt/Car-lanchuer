package pl.mati.caros.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RelayCryptoTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test fun roundTrip() {
        val msg = """{"type":"status","odo":455649,"txt":"zażółć gęślą jaźń"}"""
        val enc = RelayCrypto.encrypt(key, msg)
        assertTrue(enc.startsWith("2:"))
        assertEquals(msg, RelayCrypto.decrypt(key, enc))
    }

    @Test fun eachMessageHasNewIv() {
        assertNotEquals(RelayCrypto.encrypt(key, "a"), RelayCrypto.encrypt(key, "a"))
    }

    @Test fun wrongKeyGivesNull() {
        val enc = RelayCrypto.encrypt(key, "tajne")
        assertNull(RelayCrypto.decrypt(ByteArray(32), enc))
        assertNull(RelayCrypto.decrypt(key, "2:nie-base64!!"))
        assertNull(RelayCrypto.decrypt(key, "zwykły tekst z przekaźnika"))
    }

    /** Starszy format v1 (bez gzip, bez „2:”) – wciąż czytelny. */
    @Test fun legacyV1() {
        val iv = ByteArray(12) { it.toByte() }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val v1 = Base64.getEncoder().encodeToString(iv + c.doFinal("stary".toByteArray()))
        assertEquals("stary", RelayCrypto.decrypt(key, v1))
        assertEquals("stary", RelayCrypto.decrypt(key, "$v1\n"))
    }

    /** Kanały muszą być takie same jak w 1.x (inaczej telefon i auto się nie usłyszą). */
    @Test fun topics() {
        val t = RelayCrypto.topicOf(key)
        val p = RelayCrypto.topicOf(key, "p2c")
        assertTrue(t.startsWith("caros_"))
        assertEquals(34, t.length)
        assertNotEquals(t, p)
        assertEquals(t, RelayCrypto.topicOf(key, ""))
        // sha256 wektora testowego – stała wartość
        assertEquals("caros_" + sha("abc".toByteArray()).take(28), RelayCrypto.topicOf("abc".toByteArray()))
    }

    private fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
