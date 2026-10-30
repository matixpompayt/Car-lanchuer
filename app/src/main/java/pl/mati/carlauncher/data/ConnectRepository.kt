package pl.mati.carlauncher.data

import android.bluetooth.BluetoothAdapter
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CAR OS Connect – aplikacja na telefon.
 * Kod QR: link do pobrania aplikacji + (po #) klucz parowania, nazwa Bluetooth radia i dane auta.
 * Radio wysyła miejsce parkowania zaszyfrowane AES-GCM przez przekaźnik ntfy.sh (widzi tylko szyfrogram).
 */
object ConnectRepository {

    const val APP_PAGE = "https://github.com/matixpompayt/Caros-connect/releases/latest"
    private const val RELAY = "https://ntfy.sh/"

    /** Kiedy ostatnio wysłano dane do telefonu (0 = jeszcze nie). */
    val lastSent = MutableStateFlow(0L)
    val paired = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastSig: String? = null

    fun init() {
        paired.value = Prefs.store?.getString("connect_key", null) != null
        lastSent.value = Prefs.store?.getLong("connect_sent", 0L) ?: 0L
    }

    private fun key(create: Boolean): ByteArray? {
        val sp = Prefs.store ?: return null
        sp.getString("connect_key", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        if (!create) return null
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        sp.edit().putString("connect_key", Base64.encodeToString(k, Base64.NO_WRAP)).apply()
        paired.value = true
        return k
    }

    /** Nowy kod = stare telefony przestają dostawać dane. */
    fun resetKey() {
        Prefs.store?.edit()?.remove("connect_key")?.apply()
        paired.value = false
        lastSig = null
    }

    private fun btName(): String = try {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()?.name.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Treść kodu QR (tworzy klucz przy pierwszym użyciu i od razu wysyła ostatnie miejsce parkowania). */
    fun payload(): String {
        val k = key(create = true) ?: return APP_PAGE
        val p = Prefs.profile.value
        val json = JSONObject()
            .put("k", Base64.encodeToString(k, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .put("bt", btName())
            .put("car", p.carName)
            .put("drv", p.name)
            .toString()
        ParkingRepository.spot.value?.let { publishPark(it, force = true) }
        return APP_PAGE + "#caros1." + Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun publishPark(s: ParkingRepository.Spot, force: Boolean = false) {
        val k = key(create = false) ?: return
        val sig = "${s.lat},${s.lon},${s.time},${s.address}"
        if (!force && sig == lastSig) return
        lastSig = sig
        val msg = JSONObject()
            .put("type", "park").put("lat", s.lat).put("lon", s.lon).put("t", s.time).put("a", s.address ?: "")
            .toString()
        scope.launch {
            try {
                val body = encrypt(k, msg)
                val c = URL(RELAY + topicOf(k)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("User-Agent", "CarOS")
                try {
                    c.outputStream.use { it.write(body.toByteArray()) }
                    if (c.responseCode in 200..299) {
                        val now = System.currentTimeMillis()
                        lastSent.value = now
                        Prefs.store?.edit()?.putLong("connect_sent", now)?.apply()
                    } else {
                        lastSig = null
                    }
                } finally {
                    c.disconnect()
                }
            } catch (_: Exception) {
                lastSig = null
            }
        }
    }

    private fun topicOf(key: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(key)
        return "caros_" + h.joinToString("") { "%02x".format(it) }.take(28)
    }

    private fun encrypt(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
}
