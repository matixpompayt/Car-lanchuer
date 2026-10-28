package pl.mati.carlauncher.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * ELM327 po Bluetooth Classic (SPP). Odpytuje standardowe PIDy OBD2 (mode 01).
 * Pod 2.0 TDI BKD: prędkość, obroty, MAP (doładowanie), płyn, obciążenie, baro, napięcie (AT RV).
 */
@SuppressLint("MissingPermission")
object ObdManager {

    enum class Status { OFF, CONNECTING, CONNECTED, ERROR }

    data class ObdData(
        val speed: Int? = null,
        val rpm: Int? = null,
        val coolant: Int? = null,
        val load: Int? = null,
        val mapKpa: Int? = null,
        val baroKpa: Int? = null,
        val voltage: Float? = null,
    ) {
        /** Doładowanie względne w barach: MAP - ciśnienie atmosferyczne (domyślnie 101 kPa). */
        val boostBar: Float?
            get() = mapKpa?.let { ((it - (baroKpa ?: 101)) / 100f).coerceAtLeast(0f) }
    }

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val status = MutableStateFlow(Status.OFF)
    val data = MutableStateFlow(ObdData())
    val message = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var socket: BluetoothSocket? = null

    fun start(mac: String) {
        stop()
        job = scope.launch { runLoop(mac) }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeSocket()
        status.value = Status.OFF
        message.value = null
        data.value = ObdData()
    }

    private suspend fun runLoop(mac: String) {
        while (currentCoroutineContext().isActive) {
            try {
                status.value = Status.CONNECTING
                message.value = "Łączenie z ELM327…"
                val s = connect(mac)
                socket = s
                val io = ElmIo(s.inputStream, s.outputStream)
                initElm(io)
                status.value = Status.CONNECTED
                message.value = null
                poll(io)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = Status.ERROR
                message.value = e.message ?: e.javaClass.simpleName
                data.value = ObdData()
            } finally {
                closeSocket()
            }
            delay(5000)
        }
    }

    private fun connect(mac: String): BluetoothSocket {
        @Suppress("DEPRECATION")
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("Brak Bluetooth")
        if (!adapter.isEnabled) throw IOException("Bluetooth wyłączony")
        val device = adapter.getRemoteDevice(mac)
        adapter.cancelDiscovery()

        val factories = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Fallback dla chińskich klonów ELM327
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (factory in factories) {
            var s: BluetoothSocket? = null
            try {
                s = factory()
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                try { s?.close() } catch (_: Exception) {}
            }
        }
        throw IOException("Nie można połączyć z ELM327", last)
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private suspend fun initElm(io: ElmIo) {
        io.cmd("ATZ", 4000)
        delay(500)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) {
            io.cmd(c)
        }
        // Pierwsze zapytanie wymusza wykrycie protokołu (SEARCHING...)
        val r = io.cmd("0100", 15000)
        if (pidBytes(r, "00") == null) {
            throw IOException("ECU nie odpowiada – włącz zapłon")
        }
    }

    private suspend fun poll(io: ElmIo) {
        val failures = HashMap<String, Int>()
        var timeouts = 0
        var cycle = 0
        var d = ObdData()

        suspend fun query(pid: String): IntArray? {
            if ((failures[pid] ?: 0) >= 3) return null // PID nieobsługiwany – pomijamy
            return try {
                val bytes = pidBytes(io.cmd("01$pid"), pid)
                timeouts = 0
                failures[pid] = if (bytes == null) (failures[pid] ?: 0) + 1 else 0
                bytes
            } catch (e: IOException) {
                if (++timeouts >= 3) throw e
                null
            }
        }

        while (currentCoroutineContext().isActive) {
            query("0D")?.let { d = d.copy(speed = it[0]) }
            query("0C")?.let { if (it.size >= 2) d = d.copy(rpm = (it[0] * 256 + it[1]) / 4) }
            query("0B")?.let { d = d.copy(mapKpa = it[0]) }

            if (cycle % 5 == 0) {
                query("05")?.let { d = d.copy(coolant = it[0] - 40) }
                query("04")?.let { d = d.copy(load = it[0] * 100 / 255) }
                try {
                    val rv = io.cmd("ATRV")
                    Regex("""\d+[.,]\d+""").find(rv)?.value
                        ?.replace(',', '.')?.toFloatOrNull()
                        ?.let { d = d.copy(voltage = it) }
                } catch (_: IOException) {
                }
            }
            if (cycle % 50 == 0) {
                query("33")?.let { d = d.copy(baroKpa = it[0]) }
            }

            data.value = d
            cycle++
            delay(40)
        }
    }

    /** Wyciąga bajty danych z odpowiedzi "41 xx AA BB ..." (spacje wyłączone przez ATS0). */
    private fun pidBytes(raw: String, pid: String): IntArray? {
        val up = raw.uppercase()
        if (listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "?").any { up.contains(it) }) return null
        val header = "41$pid"
        val lines = up.replace("SEARCHING...", "")
            .split('\r', '\n')
            .map { it.replace(" ", "").trim() }
            .filter { it.isNotEmpty() }
        for (line in lines) {
            val i = line.indexOf(header)
            if (i < 0) continue
            val hex = line.substring(i + header.length).filter { it in '0'..'9' || it in 'A'..'F' }
            val n = hex.length / 2
            if (n == 0) continue
            return IntArray(n) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        }
        return null
    }

    private class ElmIo(private val input: InputStream, private val output: OutputStream) {
        suspend fun cmd(command: String, timeoutMs: Long = 2500): String {
            while (input.available() > 0) input.read()
            output.write("$command\r".toByteArray(Charsets.US_ASCII))
            output.flush()
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw IOException("Połączenie zerwane")
                    val ch = b.toChar()
                    if (ch == '>') return sb.toString()
                    sb.append(ch)
                } else {
                    delay(5)
                }
            }
            throw IOException("Brak odpowiedzi na $command")
        }
    }
}
