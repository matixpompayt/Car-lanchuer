package pl.mati.caros.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Szyfrowanie wiadomości przez publiczny przekaźnik (ntfy.sh) – przekaźnik widzi tylko szyfrogram.
 * Klucz 32 B z kodu QR. Kanały: auto → telefon = sha256(klucz), telefon → auto = sha256(klucz | "p2c").
 * Format v2: "2:" + base64(iv 12 B | AES-GCM(gzip(json))); starszy v1: base64(iv | AES-GCM(json)).
 */
object RelayCrypto {
    private val rnd = SecureRandom()

    fun topicOf(key: ByteArray, channel: String = ""): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(key)
        if (channel.isNotEmpty()) md.update(channel.toByteArray())
        return "caros_" + md.digest().joinToString("") { "%02x".format(it) }.take(28)
    }

    fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return "2:" + Base64.getEncoder().encodeToString(iv + c.doFinal(gzip(plain.toByteArray())))
    }

    /** null – zły klucz, uszkodzona albo nie nasza wiadomość. */
    fun decrypt(key: ByteArray, msg: String): String? = try {
        val v2 = msg.startsWith("2:")
        val all = Base64.getDecoder().decode((if (v2) msg.substring(2) else msg).trim())
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
        val out = c.doFinal(all, 12, all.size - 12)
        String(if (v2) gunzip(out) else out)
    } catch (_: Exception) {
        null
    }

    fun gzip(b: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(b) }
        return bos.toByteArray()
    }

    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }
}
