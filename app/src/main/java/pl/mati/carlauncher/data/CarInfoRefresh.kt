package pl.mati.carlauncher.data

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import pl.mati.carlauncher.ui.Panels
import pl.mati.carlauncher.ui.Phase
import pl.mati.carlauncher.ui.Toaster
import pl.mati.carlauncher.ui.UiState
import java.lang.ref.WeakReference

/**
 * Odświeżanie danych z auta: przejściówka CAN w radiach TopWay podaje obroty, napięcie,
 * temperatury i zasięg tylko wtedy, gdy w MainApp jest otwarty ekran „Informacje”.
 * CAR OS otwiera go więc na chwilę (aż przyjdą świeże dane, maks. kilka sekund) i sam wraca.
 *
 * Kiedy: po włączeniu zapłonu, potem co [Prefs.carInfoRefresh] min – tylko gdy CAR OS jest na
 * ekranie i nic ważnego się nie dzieje (wsteczny, rozmowa, manewr nawigacji tuż przed Tobą,
 * otwarty panel). Ręcznie: Ustawienia → Auto → „Odśwież teraz”.
 */
object CarInfoRefresh {
    private const val REQ = 4711
    /** Najkrócej / najdłużej trzymany ekran „Informacje”. */
    private const val MIN_MS = 1_500L
    private const val MAX_MS = 4_500L

    data class App(val cn: ComponentName, val label: String, val system: Boolean)

    val running = MutableStateFlow(false)
    /** Ostatnie udane odświeżenie (0 = jeszcze nie w tej jeździe). */
    val lastAt = MutableStateFlow(0L)
    val lastInfo = MutableStateFlow<String?>(null)

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var act: WeakReference<Activity>? = null
    private var loop: Job? = null
    private var cached: App? = null

    fun attach(a: Activity) {
        act = WeakReference(a)
        if (loop == null) loop = main.launch { schedule(a.applicationContext) }
    }

    // ---------- która aplikacja ----------

    /** Aplikacje z ikoną w menu (systemowe najpierw) – do ręcznego wyboru. */
    fun apps(ctx: Context): List<App> = try {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                val ai = it.activityInfo
                App(
                    ComponentName(ai.packageName, ai.name),
                    (it.loadLabel(pm)?.toString() ?: ai.name).trim(),
                    (ai.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                )
            }
            .distinctBy { it.cn }
            .sortedWith(compareByDescending<App> { it.system }.thenBy { it.label.lowercase() })
    } catch (_: Exception) {
        emptyList()
    }

    private val LABEL = Regex(
        "^(informacje|informacje o (aucie|poje[zź]dzie|samochodzie)|info(rmacje)? auta|car ?info|vehicle ?info|carinfo|auto ?info|dane auta)$",
        RegexOption.IGNORE_CASE,
    )

    /** Ekran informacji o aucie radia: po nazwie („Informacje”, „Car info”…) albo po klasie. */
    fun detect(ctx: Context): App? {
        val all = apps(ctx)
        fun radio(a: App) = a.cn.packageName.startsWith("com.ts.") || a.cn.packageName.contains("can", true) || a.cn.packageName.contains("car", true)
        return all.firstOrNull { radio(it) && LABEL.matches(it.label) }
            ?: all.firstOrNull { radio(it) && it.cn.className.contains("carinfo", true) }
            ?: all.firstOrNull { it.system && LABEL.matches(it.label) }
    }

    /** Wybrana w ustawieniach albo wykryta sama. */
    fun target(ctx: Context): App? {
        Prefs.carInfoApp.value?.let { s ->
            val cn = ComponentName.unflattenFromString(s) ?: return@let
            return apps(ctx).firstOrNull { it.cn == cn } ?: App(cn, cn.className.substringAfterLast('.'), true)
        }
        cached?.let { return it }
        return detect(ctx)?.also { cached = it }
    }

    // ---------- kiedy ----------

    private suspend fun schedule(ctx: Context) {
        // nowy zapłon = nowa jazda → odśwież zaraz po starcie
        main.launch {
            var prev: Boolean? = null
            TsCarBridge.acc.collect { on ->
                if (on == true && prev == false) lastAt.value = 0L
                prev = on
            }
        }
        delay(12_000)
        var lastTry = 0L
        while (true) {
            val mode = Prefs.carInfoRefresh.value
            val now = System.currentTimeMillis()
            val due = when {
                mode < 0 -> false
                lastAt.value == 0L -> true
                mode == 0 -> false
                else -> now - lastAt.value >= mode * 60_000L
            }
            if (due && now - lastTry > 60_000 && quiet()) {
                lastTry = now
                if (target(ctx) != null) refresh(manual = false)
            }
            delay(10_000)
        }
    }

    /** Czy można teraz na chwilę zasłonić CAR OS. */
    private fun quiet(): Boolean {
        if (running.value) return false
        if (!ShadeGuard.launcherResumed) return false                  // inna aplikacja na ekranie – nie przeszkadzaj
        if (UiState.phase.value != Phase.MAIN) return false
        if (TsCarBridge.queryFresh.value) return false                 // dane i tak świeże (ekran auta otwarty)
        if (TsCarBridge.reverse.value) return false                    // kamera cofania
        if (CallState.state.value.inCall) return false
        if (Panels.slots.isNotEmpty()) return false                    // np. wpisujesz tankowanie
        Guidance.state.value?.let { g -> if (!g.arrived && g.distanceM < 1_500) return false }  // manewr tuż przed Tobą
        return true
    }

    // ---------- samo odświeżenie ----------

    /** Otwórz ekran „Informacje” na chwilę i wróć. Zwraca false, gdy się nie dało. */
    fun refresh(manual: Boolean): Boolean {
        val a = act?.get()
        if (a == null || a.isFinishing) return false
        if (running.value) return false
        val app = target(a)
        if (app == null) {
            lastInfo.value = "Nie znaleziono ekranu informacji o aucie – wybierz aplikację niżej"
            if (manual) Toaster.show("Nie znaleziono aplikacji z danymi auta – wybierz ją w ustawieniach")
            return false
        }
        val t0 = System.currentTimeMillis()
        try {
            val i = Intent(Intent.ACTION_MAIN).setComponent(app.cn).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            // „na wynik” – wtedy można go potem zamknąć (finishActivity), a CAR OS wraca na wierzch
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, REQ)
            @Suppress("DEPRECATION")
            a.overridePendingTransition(0, 0)
        } catch (e: Exception) {
            lastInfo.value = "Nie udało się otworzyć „${app.label}” (${e.javaClass.simpleName})"
            if (manual) Toaster.show("Nie udało się otworzyć „${app.label}”")
            return false
        }
        running.value = true
        main.launch {
            // czekaj na świeże dane (zmieniły się obroty/napięcie), najdłużej kilka sekund
            var got = false
            while (true) {
                delay(250)
                val el = System.currentTimeMillis() - t0
                got = TsCarBridge.lastQueryChange > t0 + 200
                if (el >= MAX_MS || (got && el >= MIN_MS)) break
            }
            TsCarBridge.markRefreshed()
            back(a)
            lastAt.value = System.currentTimeMillis()
            lastInfo.value = if (got) "Dane odświeżone z „${app.label}”" else "Otwarto „${app.label}”, ale dane się nie zmieniły (silnik zgaszony?)"
            running.value = false
            if (manual) Toaster.show(if (got) "Dane z auta odświeżone" else "Odświeżono – bez zmian")
        }
        return true
    }

    private suspend fun back(a: Activity) {
        try { a.finishActivity(REQ) } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        try { a.overridePendingTransition(0, 0) } catch (_: Exception) {}
        delay(700)
        // ekran otworzył się w osobnym zadaniu (nie dało się go zamknąć) → CAR OS na wierzch
        if (!ShadeGuard.launcherResumed && !a.isFinishing) {
            try {
                a.startActivity(
                    Intent(a, a.javaClass).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION,
                    ),
                )
            } catch (_: Exception) {}
        }
    }
}
