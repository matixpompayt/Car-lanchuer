package pl.mati.carlauncher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.CallLog.Calls
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * Kontakty i historia połączeń telefonu sparowanego z radiem.
 * Radio (stos Bluetooth Androida, profil PBAP) po połączeniu telefonu pobiera z niego książkę
 * i historię do systemowych baz: kontakty na konto „com.android.bluetooth.pbapsink”,
 * historia do CallLog. Tu je czytamy i odświeżamy, gdy radio dociągnie nowe dane.
 */
object PhoneBook {
    const val PBAP_ACCOUNT = "com.android.bluetooth.pbapsink"

    data class Number(val number: String, val label: String)
    data class Contact(
        val id: Long, val name: String, val numbers: List<Number>,
        val starred: Boolean, val photo: String?, val fromPhone: Boolean,
    ) {
        val initials: String get() = name.split(' ', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "#" }
    }

    data class Call(
        val number: String, val name: String?, val type: Int, val date: Long,
        val duration: Long, val count: Int, val photo: String?,
    ) {
        val missed get() = type == CallLog.Calls.MISSED_TYPE || type == CallLog.Calls.REJECTED_TYPE
        val outgoing get() = type == CallLog.Calls.OUTGOING_TYPE
    }

    data class Info(
        val contacts: Int = 0, val calls: Int = 0,
        val accounts: Map<String, Int> = emptyMap(),
        val canContacts: Boolean = false, val canCalls: Boolean = false,
        val providers: List<String> = emptyList(),
        val error: String? = null,
        val loadedAt: Long = 0,
        /** Źródło kontaktów: "MainUI" (książka radia TopWay) albo "Android". */
        val source: String = "",
        val mainUi: Boolean = false,
        val mainUiEntries: Int = -1,
        val device: String? = null,
        val addr: String? = null,
    )

    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val calls = MutableStateFlow<List<Call>>(emptyList())
    val info = MutableStateFlow(Info())
    val loading = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appCtx: Context? = null
    private var observing = false
    private var pending: Job? = null
    private var byNumber: Map<String, Contact> = emptyMap()

    val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Wywołuj przy wejściu na ekran Telefon i po nadaniu uprawnień. */
    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        TsBtBridge.start(ctx)
        TsBtBridge.refreshConnectedAddr(ctx)
        observe()
        reload()
    }

    /** Dzwoni: najpierw przez MainUI (od razu przez Bluetooth), inaczej false – wtedy zwykłe ACTION_CALL. */
    fun dialViaRadio(number: String): Boolean =
        TsBtBridge.bound.value && TsBtBridge.connected() != false && TsBtBridge.dial(number)

    fun reload(delayMs: Long = 0) {
        val ctx = appCtx ?: return
        pending?.cancel()
        pending = scope.launch {
            if (delayMs > 0) delay(delayMs)
            load(ctx)
        }
    }

    /** Radio dociąga książkę po każdym połączeniu telefonu – odśwież, gdy baza się zmieni. */
    private fun observe() {
        val ctx = appCtx ?: return
        if (observing) return
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload(1500) }
        }
        var ok = false
        if (granted(ctx, Manifest.permission.READ_CONTACTS)) try {
            ctx.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, obs); ok = true
        } catch (_: Exception) {}
        if (granted(ctx, Manifest.permission.READ_CALL_LOG)) try {
            ctx.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, obs); ok = true
        } catch (_: Exception) {}
        observing = ok && granted(ctx, Manifest.permission.READ_CONTACTS) && granted(ctx, Manifest.permission.READ_CALL_LOG)
    }

    /** Ostatnie 9 cyfr – ten sam numer z +48, bez, ze spacjami. */
    fun key(n: String): String = n.filter { it.isDigit() }.takeLast(9)

    fun contactFor(number: String): Contact? = byNumber[key(number)]

    private fun load(ctx: Context) {
        loading.value = true
        val canC = granted(ctx, Manifest.permission.READ_CONTACTS)
        val canL = granted(ctx, Manifest.permission.READ_CALL_LOG)
        var err: String? = null
        val accounts = HashMap<String, Int>()
        val system = ArrayList<Contact>()
        if (canC) try {
            system += readContacts(ctx, accounts)
        } catch (e: Exception) { err = "Kontakty: ${e.javaClass.simpleName}" }
        val addr = TsBtBridge.currentAddr()
        // książka z MainUI (tak jak w aplikacji telefonu radia) – tylko dla połączonego telefonu, jeśli da się go rozpoznać
        val pb = TsBtBridge.phonebook()
        val mine = pb?.let { all -> if (addr != null && all.any { it.addr.equals(addr, true) }) all.filter { it.addr.equals(addr, true) } else all }.orEmpty()
        val list = ArrayList<Contact>()
        val source: String
        if (mine.isNotEmpty()) {
            source = "MainUI"
            val sysByKey = HashMap<String, Contact>()
            system.forEach { c -> c.numbers.forEach { n -> sysByKey.putIfAbsent(key(n.number), c) } }
            val grouped = LinkedHashMap<String, MutableList<TsBtBridge.Entry>>()
            mine.forEach { e -> grouped.getOrPut(e.name.lowercase(Locale("pl"))) { ArrayList() } += e }
            var id = 1L
            grouped.values.forEach { es ->
                val nums = LinkedHashMap<String, Number>()
                var photo: String? = null
                es.forEach { e ->
                    val k = key(e.num).ifEmpty { e.num }
                    val sys = sysByKey[key(e.num)]
                    if (photo == null) photo = sys?.photo
                    val label = sys?.numbers?.firstOrNull { key(it.number) == key(e.num) }?.label.orEmpty()
                    if (!nums.containsKey(k)) nums[k] = Number(e.num, label)
                }
                list += Contact(-(id++), es.first().name, nums.values.toList(), es.any { it.collect == 1 }, photo, true)
            }
            val col = Collator.getInstance(Locale("pl", "PL")).apply { strength = Collator.PRIMARY }
            list.sortWith { x, y -> col.compare(x.name, y.name) }
        } else {
            source = if (system.isNotEmpty()) "Android" else ""
            list += system
        }
        val map = HashMap<String, Contact>()
        list.forEach { c -> c.numbers.forEach { n -> val k = key(n.number); if (k.length >= 6) map.putIfAbsent(k, c) } }
        byNumber = map
        contacts.value = list

        val callList = ArrayList<Call>()
        if (canL) try {
            callList += readCalls(ctx, addr)
        } catch (e: Exception) { err = (err?.let { "$it; " } ?: "") + "Historia: ${e.javaClass.simpleName}" }
        calls.value = callList

        info.value = Info(
            list.size, callList.sumOf { it.count }, accounts, canC, canL, btProviders(ctx), err, System.currentTimeMillis(),
            source, TsBtBridge.bound.value, pb?.size ?: -1, TsBtBridge.deviceName(), addr,
        )
        loading.value = false
        if (!observing) observe()
    }

    private fun readContacts(ctx: Context, accounts: HashMap<String, Int>): List<Contact> {
        val base = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.STARRED, Phone.PHOTO_THUMBNAIL_URI)
        val withAccount = base + ContactsContract.RawContacts.ACCOUNT_TYPE
        val cur = try {
            ctx.contentResolver.query(Phone.CONTENT_URI, withAccount, null, null, null)
        } catch (_: Exception) {
            ctx.contentResolver.query(Phone.CONTENT_URI, base, null, null, null)
        } ?: return emptyList()
        data class Acc(var name: String, val nums: LinkedHashMap<String, Number>, var starred: Boolean, var photo: String?, var phone: Boolean)
        val byId = LinkedHashMap<Long, Acc>()
        cur.use { c ->
            val iId = c.getColumnIndex(Phone.CONTACT_ID)
            val iName = c.getColumnIndex(Phone.DISPLAY_NAME_PRIMARY)
            val iNum = c.getColumnIndex(Phone.NUMBER)
            val iType = c.getColumnIndex(Phone.TYPE)
            val iLabel = c.getColumnIndex(Phone.LABEL)
            val iStar = c.getColumnIndex(Phone.STARRED)
            val iPhoto = c.getColumnIndex(Phone.PHOTO_THUMBNAIL_URI)
            val iAcc = c.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val num = c.getString(iNum)?.trim().orEmpty()
                if (num.isEmpty()) continue
                val name = c.getString(iName)?.trim().orEmpty().ifEmpty { num }
                val acc = if (iAcc >= 0) c.getString(iAcc).orEmpty() else ""
                accounts[acc.ifEmpty { "lokalne" }] = (accounts[acc.ifEmpty { "lokalne" }] ?: 0) + 1
                val label = try {
                    Phone.getTypeLabel(ctx.resources, c.getInt(iType), c.getString(iLabel)).toString()
                } catch (_: Exception) { "" }
                val a = byId.getOrPut(id) { Acc(name, LinkedHashMap(), false, null, false) }
                val k = key(num).ifEmpty { num }
                if (!a.nums.containsKey(k)) a.nums[k] = Number(num, label)
                if (c.getInt(iStar) == 1) a.starred = true
                if (a.photo == null) a.photo = c.getString(iPhoto)
                if (acc == PBAP_ACCOUNT) a.phone = true
            }
        }
        val col = Collator.getInstance(Locale("pl", "PL")).apply { strength = Collator.PRIMARY }
        return byId.map { (id, a) -> Contact(id, a.name, a.nums.values.toList(), a.starred, a.photo, a.phone) }
            .sortedWith { x, y -> col.compare(x.name, y.name) }
    }

    /** Historia z systemowego rejestru – radio zapisuje ją z adresem telefonu w subscription_id (tak czyta ją MainUI). */
    private fun readCalls(ctx: Context, addr: String?): List<Call> {
        val cur = ctx.contentResolver.query(
            Calls.CONTENT_URI, arrayOf(Calls.NUMBER, Calls.CACHED_NAME, Calls.TYPE, Calls.DATE, Calls.DURATION, Calls.PHONE_ACCOUNT_ID),
            null, null, "${Calls.DATE} DESC",
        ) ?: return emptyList()
        // tylko połączony telefon (jeśli w rejestrze są wpisy z jego adresem)
        val onlyAddr = addr?.takeIf { a ->
            try {
                ctx.contentResolver.query(Calls.CONTENT_URI, arrayOf(Calls.DATE), "${Calls.PHONE_ACCOUNT_ID}=?", arrayOf(a), null)?.use { it.count > 0 } == true
            } catch (_: Exception) { false }
        }
        val out = ArrayList<Call>()
        cur.use { c ->
            val iNum = c.getColumnIndex(Calls.NUMBER)
            val iName = c.getColumnIndex(Calls.CACHED_NAME)
            val iType = c.getColumnIndex(Calls.TYPE)
            val iDate = c.getColumnIndex(Calls.DATE)
            val iDur = c.getColumnIndex(Calls.DURATION)
            val iAcc = c.getColumnIndex(Calls.PHONE_ACCOUNT_ID)
            var n = 0
            while (c.moveToNext() && n < 600) {
                n++
                if (onlyAddr != null && iAcc >= 0 && !onlyAddr.equals(c.getString(iAcc), true)) continue
                val num = c.getString(iNum)?.trim().orEmpty()
                val type = c.getInt(iType)
                val date = c.getLong(iDate)
                val prev = out.lastOrNull()
                // kilka połączeń z rzędu z tym samym numerem i tego samego rodzaju = jeden wiersz (3)
                if (prev != null && key(prev.number) == key(num) && prev.type == type && prev.date - date < 6 * 3600_000L) {
                    out[out.lastIndex] = prev.copy(count = prev.count + 1)
                    continue
                }
                val contact = contactFor(num)
                val cached = c.getString(iName)?.trim()?.takeIf { it.isNotEmpty() }
                out += Call(num, contact?.name ?: cached, type, date, c.getLong(iDur), 1, contact?.photo)
                if (out.size >= 150) break
            }
        }
        return out
    }

    /** Diagnostyka: czy aplikacja Bluetooth radia ma własne bazy (gdyby nie zapisywała do systemowych). */
    private fun btProviders(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val out = ArrayList<String>()
        for (pkg in listOf("com.ts.bt", "com.ts.MainUI", "com.android.bluetooth", "com.syu.bt", "com.tw.bt", "com.microntek.bluetooth")) {
            try {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS)
                val prov = pi.providers?.joinToString(", ") { it.authority + if (it.exported) "" else " (prywatny)" }.orEmpty()
                out += "$pkg: ${prov.ifEmpty { "brak baz" }}"
            } catch (_: Exception) {
            }
        }
        return out
    }
}
