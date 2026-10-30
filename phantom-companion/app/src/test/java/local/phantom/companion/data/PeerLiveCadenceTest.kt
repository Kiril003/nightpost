package local.phantom.companion.data

import local.phantom.companion.core.data.peers.PEER_LIVE_FRESH_WINDOW_MS
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerShare
import local.phantom.companion.core.data.peers.ShareMode
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.core.net.peer.PeerLiveShare
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ДВА ПОРОГИ НА ОДНЕ ПИТАННЯ — ТУТ І ЛИШЕ ТУТ ЇХ ВИДНО РАЗОМ.
 *
 * Відправник живе в `core-net` і мовчить щонайдовше
 * [PeerLiveShare.MAX_GAP_MS] плюс такт. Приймач живе в `core-data` і судить вік
 * точки за [PEER_LIVE_FRESH_WINDOW_MS]. Жоден модуль не бачить другого, тож
 * розходження між ними компілятор пропускав — і воно було: 240 с тиші проти
 * 90 с свіжості, тобто чужа точка блідла на людині, яка стоїть і чесно
 * озивається, дві третини часу.
 *
 * Тому число не дублюється, а ЗВІРЯЄТЬСЯ: цей сторож живе в `:app`, бо це
 * єдиний модуль, який бачить обидві сторони домовленості.
 *
 * GATE LAW: сторож читає константи, а не джерело файлом — але Gradle однаково
 * не перезапустить його від правки в чужому модулі без `--rerun`.
 */
class PeerLiveCadenceTest {

    /** Найдовша ЧЕСНА тиша відправника: стеля квантована кроком годинника. */
    private val worstSilenceMs = PeerLiveShare.MAX_GAP_MS + PeerLiveShare.TICK_MS

    @Test
    fun `вікно свіжості накриває найдовшу чесну тишу відправника`() {
        assertTrue(
            "приймач блідне на тому, хто озивається за домовленістю: " +
                "тиша до $worstSilenceMs мс проти вікна $PEER_LIVE_FRESH_WINDOW_MS мс",
            PEER_LIVE_FRESH_WINDOW_MS >= worstSilenceMs,
        )
    }

    @Test
    fun `але не вдвічі ширше — замовклий телефон мусить збліднути`() {
        assertTrue(
            "вікно $PEER_LIVE_FRESH_WINDOW_MS мс тримає «живим» того, від кого " +
                "не було двох реплік поспіль",
            PEER_LIVE_FRESH_WINDOW_MS <= 2 * worstSilenceMs,
        )
    }

    @Test
    fun `точка живого сеансу лишається свіжою через стелю мовчання`() {
        val at = 1_000_000L
        val p = live(at)
        assertTrue(
            "точка збліднула рівно тоді, коли відправник ще в межах домовленості",
            p.freshAt(at + worstSilenceMs),
        )
        assertFalse(
            "точка лишилась «щойно» після пропущеної репліки",
            p.freshAt(at + PEER_LIVE_FRESH_WINDOW_MS + 1),
        )
    }

    @Test
    fun `разова точка судиться вужчим вікном — у неї немає домовленості`() {
        val at = 1_000_000L
        val once = live(at).copy(share = PeerShare(ShareMode.ONE_SHOT))
        assertFalse(
            "разове місце з розмови вважається «щойно» за живим кроком, " +
                "хоча ніхто не обіцяв його оновлювати",
            once.freshAt(at + worstSilenceMs),
        )
    }

    private fun live(atMs: Long) = PeerPoint(
        nodeId = "n",
        threadId = "n",
        name = "Марта",
        lat = 50.45,
        lon = 30.52,
        atMs = atMs,
        share = PeerShare(ShareMode.LIVE, atMs + 15 * 60_000L),
    )
}
