package local.phantom.companion.service

import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.data.BodyLadder

/**
 * «Зупинити все» у шторці: одна дія, без відкриття застосунку.
 *
 * Діє лише на цьому телефоні й не питає ПК — вимикач, що чекає згоди другої
 * сторони, не вимикає нічого саме тоді, коли та сторона не слухається.
 */
class StopAllTile : TileService() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        paint()
    }

    override fun onClick() {
        super.onClick()
        val ladder = BodyLadder.of(this)
        if (ladder.stopped.value) ladder.resume() else ladder.stopAll()
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val stopped = BodyLadder.of(this).stopped.value
        tile.label = getString(R.string.stop_all_tile)
        tile.subtitle = if (stopped) "Компʼютер нічого не може" else "Компʼютер діє за дозволами"
        tile.state = if (stopped) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
