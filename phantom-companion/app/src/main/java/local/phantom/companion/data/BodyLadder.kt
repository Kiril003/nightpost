package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.tool.permission.SymbioteStop

/**
 * Драбина дозволів телефона: що спарений ПК може тут робити.
 *
 * Та сама, що й на ПК (`symbiote/ladder.py`), але своя: ПК не вирішує за
 * телефон. Окремо по сферах, після спарювання — лише «бачити». Незворотне
 * вище за «так» не піднімається. «Зупинити все» діє тут, хоч би що казав ПК.
 */
class BodyLadder(private val shelf: Shelf) {

    interface Shelf {
        fun read(key: String): String?
        fun write(key: String, value: String)
    }

    enum class Sphere(
        val key: String,
        val title: String,
        @androidx.annotation.StringRes val label: Int,
    ) {
        PLACE("place", "Місце", local.phantom.companion.R.string.sphere_place),
        SOUND("sound", "Звук і голос", local.phantom.companion.R.string.sphere_sound),
        NOTICES("notices", "Сповіщення", local.phantom.companion.R.string.sphere_notices),
        CLIPBOARD("clipboard", "Буфер і посилання", local.phantom.companion.R.string.sphere_clipboard),
        SCREEN("screen", "Екран", local.phantom.companion.R.string.sphere_screen),
        KEYBOARD("keyboard", "Клавіатура", local.phantom.companion.R.string.sphere_keyboard),
    }

    enum class Rung(val key: String, val title: String) {
        OFF("off", "Вимкнено"),
        SEE("see", "Бачити"),
        SUGGEST("suggest", "Пропонувати"),
        ASK("ask", "Робити з «так»"),
        REPORT("report", "Робити і звітувати"),
        SILENT("silent", "Мовчки"),
    }

    enum class Kind { READ, ACT, STOP }

    data class Rule(
        val sphere: Sphere?,
        val kind: Kind,
        val title: String,
        val irreversible: Boolean = false,
    )

    sealed interface Verdict {
        data class Do(val report: Boolean) : Verdict
        data object Ask : Verdict
        data object Suggest : Verdict
        data class Refuse(val why: String) : Verdict
    }

    private val _stopped = MutableStateFlow(shelf.read(KEY_STOP) == "1")
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    init {
        if (_stopped.value) SymbioteStop.stopAll() else SymbioteStop.resume()
    }

    fun level(sphere: Sphere): Rung =
        Rung.entries.firstOrNull { it.key == shelf.read(sphere.key) } ?: Rung.SEE

    fun set(sphere: Sphere, rung: Rung) {
        val capped = if (irreversible(sphere) && rung.ordinal > Rung.ASK.ordinal) Rung.ASK else rung
        shelf.write(sphere.key, capped.key)
    }

    /** Як `ladder.capped` на ПК: сфера з незворотним дієсловом вище за «так» не стає. */
    fun irreversible(sphere: Sphere): Boolean =
        RULES.values.any { it.sphere == sphere && it.irreversible }

    fun stopAll() {
        shelf.write(KEY_STOP, "1")
        _stopped.value = true
        SymbioteStop.stopAll()
    }

    fun resume() {
        shelf.write(KEY_STOP, "0")
        _stopped.value = false
        SymbioteStop.resume()
    }

    fun judge(verb: String): Verdict {
        val rule = RULES[verb] ?: return Verdict.Refuse("Телефон цього не вміє")
        if (rule.kind == Kind.STOP) return Verdict.Do(report = false)
        if (_stopped.value) return Verdict.Refuse("Усе зупинено на телефоні")
        val sphere = rule.sphere ?: return Verdict.Refuse("Телефон цього не вміє")
        val rung = level(sphere)
        if (rung == Rung.OFF) return Verdict.Refuse("Для «${sphere.title}» доступ вимкнено")
        if (rule.kind == Kind.READ) return Verdict.Do(report = false)
        return when {
            rung == Rung.SEE -> Verdict.Refuse("Для «${sphere.title}» дозволено лише бачити")
            rung == Rung.SUGGEST -> Verdict.Suggest
            rule.irreversible || rung == Rung.ASK -> Verdict.Ask
            rung == Rung.REPORT -> Verdict.Do(report = true)
            else -> Verdict.Do(report = false)
        }
    }

    companion object {
        internal const val KEY_STOP = "stop_all"
        internal const val PREFS = "phantom_body_ladder"

        /**
         * Синхронно відновлює стан [SymbioteStop] із тих самих SharedPreferences,
         * які читає [BodyLadder], до запуску фонових потоків у `PhantomApp.onCreate`.
         */
        fun restoreStopSynchronously(context: Context) {
            val raw = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_STOP, null)
            syncStopFromRaw(raw)
        }

        internal fun syncStopFromRaw(raw: String?) {
            if (raw == "1") SymbioteStop.stopAll() else SymbioteStop.resume()
        }

        val RULES: Map<String, Rule> = mapOf(
            "phone.locate" to Rule(Sphere.PLACE, Kind.READ, "віддати місце"),
            "phone.wifi_scan" to Rule(Sphere.PLACE, Kind.READ, "показати, що бачить радіо"),
            "phone.ring" to Rule(Sphere.SOUND, Kind.ACT, "подати звук"),
            "phone.find" to Rule(Sphere.SOUND, Kind.ACT, "знайти телефон"),
            "phone.speak" to Rule(Sphere.SOUND, Kind.ACT, "сказати вголос"),
            "phone.notify" to Rule(Sphere.NOTICES, Kind.ACT, "показати сповіщення"),
            "phone.stop" to Rule(null, Kind.STOP, "перестати"),
            // SEE — очі симбіота. Читання: драбина не питає, лише сфера має бути ввімкнена.
            "phone.screen.read" to Rule(Sphere.SCREEN, Kind.READ, "прочитати екран"),
            "phone.notifications.recent" to Rule(Sphere.NOTICES, Kind.READ, "показати сповіщення"),
            "phone.state" to Rule(Sphere.PLACE, Kind.READ, "сказати, як почувається телефон"),
            // ACT — руки симбіота. Набір тексту незворотний (як на ПК): завжди з «так».
            "phone.app.open" to Rule(Sphere.SCREEN, Kind.ACT, "відкрити застосунок"),
            "phone.tap" to Rule(Sphere.SCREEN, Kind.ACT, "торкнутись екрана"),
            "phone.type" to Rule(Sphere.KEYBOARD, Kind.ACT, "набрати текст", irreversible = true),
            "phone.scroll" to Rule(Sphere.SCREEN, Kind.ACT, "прокрутити екран"),
            "phone.back" to Rule(Sphere.SCREEN, Kind.ACT, "натиснути «назад»"),
            "phone.home" to Rule(Sphere.SCREEN, Kind.ACT, "піти на головний екран"),
            "phone.intent" to Rule(Sphere.CLIPBOARD, Kind.ACT, "відкрити посилання чи дію"),
            "phone.clipboard.set" to Rule(Sphere.CLIPBOARD, Kind.ACT, "покласти в буфер"),
        )

        @Volatile
        private var shared: BodyLadder? = null

        /** Одна драбина на процес: плитка «Зупинити все» і виконавець бачать ту саму. */
        fun of(context: Context): BodyLadder = shared ?: synchronized(this) {
            shared ?: BodyLadder(PrefsShelf(context.applicationContext)).also { shared = it }
        }

        private class PrefsShelf(context: Context) : Shelf {
            private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            override fun read(key: String): String? = prefs.getString(key, null)
            override fun write(key: String, value: String) {
                prefs.edit().putString(key, value).commit()
            }
        }
    }
}
