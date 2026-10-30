package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * СКАРГА ВЛАСНИКА №9 — «не перемикається назад на справжній GPS, коли той
 * повернувся».
 *
 * Заміряно програвачем (`scripts/replay-blackbox.py` на `_grant/records/
 * reb-20260804`): епізод недовіри тривав **4002 с — 67 хвилин**, і всередині
 * нього надходило **32 фікси, з похибкою ≤30 м**. Тобто ~один фікс на 125 с:
 * небо було, воно казало правду про свою точність, і його не брали.
 *
 * ## Що саме тут відтворюється
 *
 * Обидва шляхи повернення довіри після сліпоти рахували витримку в **приходах
 * фіксів**, а не в секундах:
 *
 *  1. Свідок БЕЗ стояння (інерційний бюджет > [CustomLocationEngine
 *     .WITNESS_STANDING_M]) — єдиний вихід був «два фікси, що узгоджуються
 *     МІЖ СОБОЮ», але узгодження додатково вимагало, щоб вони прийшли в
 *     одному вікні (45 с). Чип, який відповідає раз на 125 с, цю умову не
 *     може виконати НІКОЛИ: кожен кандидат протухає раніше, ніж народиться
 *     наступний фікс. А гілка закінчувалась жорстким `return` — поступки
 *     нижче вона не діставала. Це не «повільно», це замок.
 *  2. Свідок ЗІ стоянням — 20 відмов, тоді поступка. Двадцять приходів
 *     задумувались як «~20 с на небі 1 Гц» (так і написано в константі), але
 *     на кадансі запису 20 приходів = **2500 с, 42 хвилини**.
 *
 * Каданс у тестах узятий із того самого виміру: [SPARSE_MS] = 125 с.
 *
 * ## Межа цих тестів, названа вголос
 *
 * Запис у цьому дереві недоступний, тож тести не переграють його дослівно —
 * вони відтворюють МЕХАНІЗМ на синтетичному кадансі. Запис не розрізняє, яка
 * саме з двох гілок тримала ті 67 хвилин (для цього треба `kal`-рядок із
 * `deadReckonedM`), тому полагоджено обидві, і кожна має власний тест.
 */
class SkyReturnCadenceTest {

    private val lat0 = 50.4501
    private val lon0 = 30.5234

    private fun northOf(m: Double) = lat0 + Math.toDegrees(m / 6_371_000.0)

    private fun eastOf(m: Double) =
        lon0 + Math.toDegrees(m / (6_371_000.0 * Math.cos(Math.toRadians(lat0))))

    private fun fix(
        northM: Double,
        eastM: Double = 0.0,
        speed: Float = 11f,
        acc: Float = 5f,
    ) = GpsSample(
        lat = northOf(northM),
        lon = eastOf(eastM),
        accuracyM = acc,
        speedMps = speed,
        bearingDeg = 0f,
    )

    /** Телефон лежить рівно, платформа мовчить. */
    private fun imu() = ImuSample(
        accelX = 0f, accelY = 0f, accelZ = 9.81f,
        gyroX = 0f, gyroY = 0f, gyroZ = 0f,
        rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
    )

    /** Датчики на 50 Гц, тік раз на секунду — рівно як їх годує застосунок. */
    private fun coast(engine: CustomLocationEngine, fromMs: Long, forMs: Long): Long {
        var t = fromMs
        val end = fromMs + forMs
        var nextTick = fromMs + 1_000
        while (t < end) {
            t += 20
            engine.onImu(imu(), t)
            if (t >= nextTick) {
                engine.onTick(t)
                nextTick += 1_000
            }
        }
        return t
    }

    private fun blind(engine: CustomLocationEngine): Boolean =
        engine.trust.value == LocationTrust.SPOOFED ||
            engine.trust.value == LocationTrust.JAMMED

    // ── 1. Свідок без стояння: вихід, якого рідкісне небо не могло досягти ──

    /**
     * Два кілометри тунелю, тоді оператор звернув і став — а небо повертається
     * з кадансом глухого приміщення.
     *
     * Арифметика, щоб її можна було перевірити очима:
     *  · 120 с сліпоти на 11 м/с ⇒ інерція пронесла ~1320 м, тож інерційний
     *    бюджет 0,8·1320 + 200 ≈ 1256 м — це ПОЗА [CustomLocationEngine
     *    .WITNESS_STANDING_M], свідок уже без стояння;
     *  · правда — за 4 км на схід, тобто розбіжність ~4,2 км проти допуску
     *    ~1,3 км: небо мусить довести себе, і це правильно;
     *  · оператор СТОЇТЬ, тож два його фікси лежать в одній точці — це і є
     *    доказ, якого вимагає конструкція.
     *
     * **Чому червонів на старому коді:** узгодження двох фіксів вимагало
     * `gapS·1000 <= REACQUIRE_CONSIST_GAP_MS` (45 с). При кадансі 125 с
     * кандидат протухав завжди, `consistent` не міг стати істиною жодного
     * разу, а гілка `!witnessStands` завершувалась `return` — поступка нижче
     * (`if (apart > budget)`) була для неї недосяжна. Двигун лишався SPOOFED
     * до кінця сесії; цикл нижче проганяє 12 фіксів (25 хвилин) і жодного
     * повернення не бачив.
     */
    @Test
    fun `рідкісне небо повертає довіру, коли інерційний свідок уже без стояння`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)
        val beforeTunnel = 11.0 * 29

        t = coast(engine, t, 120_000)
        assertEquals("тунель — це сліпота", LocationTrust.JAMMED, engine.trust.value)

        // Оператор звернув на схід і зупинився; сліпий числівник цього не
        // бачив і далі несе його на північ.
        val truth = { fix(northM = beforeTunnel, eastM = 4_000.0, speed = 0f, acc = 25f) }

        val skyBackAtMs = t
        var recoveredAtMs = -1L
        var fixesToRecover = 0
        var trustAtRecovery = LocationTrust.SEARCHING
        var refusalsAtRecovery = 0L
        val refusedBefore = engine.refusedFixes

        for (i in 1..12) {
            engine.onGps(truth(), t)
            if (recoveredAtMs < 0L && !blind(engine)) {
                recoveredAtMs = t
                fixesToRecover = i
                trustAtRecovery = engine.trust.value
                refusalsAtRecovery = engine.refusedFixes - refusedBefore
            }
            t = coast(engine, t, SPARSE_MS)
        }

        assertTrue(
            "небо повернулось і 12 разів поспіль казало те саме за ${(t - skyBackAtMs) / 1000} с " +
                "(каданс ${SPARSE_MS / 1000} с), а двигун так і лишився ${engine.trust.value} — " +
                "вихід із недовіри недосяжний для рідкісного чипа: ${engine.lastRejection.value}",
            recoveredAtMs >= 0L,
        )
        assertEquals(
            "повернення мусить коштувати ДРУГОГО фікса — один не доводить нічого",
            2,
            fixesToRecover,
        )
        assertEquals(
            "перший фікс мусив бути відкинутий як кандидат, і рівно один",
            1L,
            refusalsAtRecovery,
        )
        assertEquals(
            "поступка небу, з яким інерція не згодна, — це DEGRADED, ніколи TRUSTED",
            LocationTrust.DEGRADED,
            trustAtRecovery,
        )
        assertTrue(
            "повернення забрало ${(recoveredAtMs - skyBackAtMs) / 1000} с — довше за два приходи чипа",
            recoveredAtMs - skyBackAtMs <= 2 * SPARSE_MS,
        )
    }

    // ── 2. Свідок зі стоянням: витримка в приходах, а не в секундах ─────────

    /**
     * Телефон лежить у кімнаті. Небо мовчить пів хвилини, тоді повертається
     * чесним кімнатним фіксом: за 356 м, із власним зізнанням ±30 м.
     *
     * Тіло СТОЇТЬ, тож інерція нікуди не пішла (deadReckonedM = 0) і свідок
     * зберігає стояння: допуск 0,8·0 + 200 + 30 = 230 м проти розбіжності
     * 356 м. Відмова тут доречна — двигун мусить вимагати доказу. Питання в
     * тому, СКІЛЬКИ він його вимагає.
     *
     * **Чому червонів на старому коді:** витримка була `staleRefusals <
     * REACQUIRE_GRACE_FIXES` — двадцять ПРИХОДІВ. Сама константа описує їх як
     * «~20 s at 1 Hz»; на кадансі 125 с ті самі двадцять приходів — це 2500 с,
     * 41 хвилина, і повернення ставалось аж на 21-му фіксі. Цикл нижче знаходив
     * повернення на 2500-й секунді й падав на межі в 10 хвилин.
     */
    @Test
    fun `витримка міряється секундами оператора, а не приходами чипа`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(20) {
            engine.onGps(fix(0.0, speed = 0f, acc = 5f), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)

        // Небо мовчить. Тіло стоїть, тож це не глушіння — просто слабке небо.
        t = coast(engine, t, 30_000)

        val indoor = { fix(356.0, speed = 0f, acc = 30f) }
        val skyBackAtMs = t
        var recoveredAtMs = -1L
        var refusalsAtRecovery = 0L
        val refusedBefore = engine.refusedFixes

        for (i in 1..25) {
            engine.onGps(indoor(), t)
            if (recoveredAtMs < 0L && !blind(engine)) {
                recoveredAtMs = t
                refusalsAtRecovery = engine.refusedFixes - refusedBefore
            }
            t = coast(engine, t, SPARSE_MS)
        }

        assertTrue(
            "двигун так і не взяв чесне кімнатне небо за ${(t - skyBackAtMs) / 1000} с",
            recoveredAtMs >= 0L,
        )
        val heldS = (recoveredAtMs - skyBackAtMs) / 1000
        assertTrue(
            "недовіра трималась $heldS с (${heldS / 60} хв), поки небо відповідало як могло — " +
                "витримку рахують у приходах чипа, а не в секундах оператора",
            heldS <= MAX_HOLDOUT_S,
        )
        assertTrue(
            "але вона мусить коштувати кількох відмов, а не однієї: було $refusalsAtRecovery",
            refusalsAtRecovery >= CustomLocationEngine.REACQUIRE_MIN_REFUSALS,
        )
    }

    // ── 3. Захист, який ці правки НЕ мають права послабити ──────────────────

    /**
     * Дзеркало до тесту 2: один-єдиний фікс, хай і після довгої тиші, довіри
     * не повертає. Це не регресійний тест (на старому коді він теж зелений) —
     * це замок на самій правці: годинник скорочує витримку, але ніколи не
     * нижче [CustomLocationEngine.REACQUIRE_MIN_REFUSALS] відмов.
     */
    @Test
    fun `жодна пауза не робить один фікс доказом`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(20) {
            engine.onGps(fix(0.0, speed = 0f, acc = 5f), t)
            t = coast(engine, t, 1_000)
        }
        t = coast(engine, t, 30_000)

        repeat(CustomLocationEngine.REACQUIRE_MIN_REFUSALS) {
            engine.onGps(fix(356.0, speed = 0f, acc = 30f), t)
            assertEquals(
                "довіра повернулась раніше за ${CustomLocationEngine.REACQUIRE_MIN_REFUSALS} " +
                    "відмови — доказ підмінили годинником",
                LocationTrust.SPOOFED,
                engine.trust.value,
            )
            t = coast(engine, t, SPARSE_MS)
        }
    }

    /**
     * І головне: на небі 1 Гц — тому, під яке калібрували 20 приходів —
     * поведінка мусить лишитись побайтово тією самою. Годинник у 30 с
     * навмисно ДОВШИЙ за 20 с, які ті двадцять приходів займають на 1 Гц,
     * тож на проєктному кадансі він не встигає нічого сказати.
     *
     * Сценарій — глушіння, тоді «небо» за 8 км: класичне jam-then-spoof.
     */
    @Test
    fun `на небі 1 Гц витримка проти jam-then-spoof не скоротилась`() {
        val engine = CustomLocationEngine()
        var t = 1_000L

        repeat(30) { i ->
            engine.onGps(fix(11.0 * i), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(LocationTrust.TRUSTED, engine.trust.value)

        t = coast(engine, t, 60_000)
        assertEquals(LocationTrust.JAMMED, engine.trust.value)

        repeat(15) {
            engine.onGps(fix(8_000.0), t)
            t = coast(engine, t, 1_000)
        }
        assertEquals(
            "на 1 Гц підміну після глушіння мусить тримати лічильник приходів, " +
                "а не годинник — 15 с недостатньо, щоб здатись",
            LocationTrust.SPOOFED,
            engine.trust.value,
        )
    }

    private companion object {
        /**
         * Каданс із заміряного запису: 32 фікси за 4002 с ≈ один на 125 с.
         * Це не вигадане число — це те, як відповідає чип, який ледве бачить
         * небо, і рівно те, під що жодна з витримок не була порахована.
         */
        const val SPARSE_MS = 125_000L

        /**
         * Скільки недовіра має право протриматись, поки небо відповідає.
         * Десять хвилин — свідомо щедра межа: вона не описує ціль (ціль —
         * чотири прихóди чипа), вона лише відрізає 67 хвилин зі скарги.
         */
        const val MAX_HOLDOUT_S = 10 * 60
    }
}
