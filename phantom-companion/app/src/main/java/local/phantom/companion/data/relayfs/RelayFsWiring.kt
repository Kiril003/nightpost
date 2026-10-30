package local.phantom.companion.data.relayfs

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.net.peer.RelayFs
import local.phantom.companion.core.net.peer.RelayFsKeys
import local.phantom.companion.core.net.peer.RelayFsStatus
import local.phantom.companion.ui.messenger.WebMessengerRelayFs
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.EncryptedSlot
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Конверт v5 (docs/crypto/RELAY_FS.md) у живому телефоні.
 *
 *  * ключі, що стираються, — у зашифрованому файлі (приватні SPK/OPK);
 *  * пачки prekey контактів приходять листами PH-PKB: усередині звичайних
 *    конвертів пари; підпис SPK перевіряється Ed25519 із реєстру;
 *  * свої пачки роздаються ЛИШЕ з увімкненим [RelayFs.Flag] — до того телефон
 *    поводиться рівно як досі (читати v5 уміє, слати — ні);
 *  * прапорець вмикає людина: «Профіль › Для розробника» (RELAY_FS.md §10),
 *    і він переживає перезапуск. Увімкнення будить роздачу пачок одразу.
 *
 * Резервної копії ключів немає і не буде (RELAY_FS.md §11): системний бекап
 * несе лише autobackup/snapshot.json (без цих ключів), а ключ особи relay-fs
 * привʼязаний до Keystore апарата — відновлений телефон тут є новою особою.
 */
class RelayFsWiring(private val container: AppContainer) : WebMessengerRelayFs.Port {

    private val slot = EncryptedSlot(container.applicationContext, FILE_NAME, durable = true)

    val keys = RelayFsKeys(object : RelayFsKeys.Slot {
        override fun read(): String? = slot.read(KEY)
        override fun write(value: String) = slot.write(KEY, value)
    })

    private val flagPrefs = container.applicationContext.getSharedPreferences(FLAG_FILE, Context.MODE_PRIVATE)

    private val started = AtomicBoolean(false)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        RelayFs.Flag.enabled = flagPrefs.getBoolean(FLAG_KEY, false)
    }

    override fun setEnabled(on: Boolean) {
        flagPrefs.edit().putBoolean(FLAG_KEY, on).commit()
        RelayFs.Flag.enabled = on
        if (on) wake.trySend(Unit)
    }

    override fun view(): String = RelayFsStatus.json(
        RelayFsStatus.of(
            keys,
            container.peers.peers.value.map { RelayFsStatus.Person(it.id, it.name, it.dhPubKey) },
            RelayFs.Flag.enabled,
            System.currentTimeMillis(),
        ),
    )

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        container.mesh.relayFsKeys = keys
        // Пачки контактів: лише від людей із реєстру і лише з підписом їхнього Ed25519.
        scope.launch {
            val seen = HashSet<String>()
            container.mesh.outbox.collect { log ->
                for (m in log) {
                    if (!RelayFs.isBundle(m.body) || !seen.add(m.id)) continue
                    val peer = container.peers.peers.value.firstOrNull { it.id == m.fromId } ?: continue
                    val ed = runCatching { java.util.Base64.getDecoder().decode(peer.pubKey) }.getOrNull() ?: continue
                    val bundle = RelayFs.decodeBundle(m.body, ed) ?: continue
                    keys.absorb(m.fromId, bundle)
                }
            }
        }
        // Свої пачки — лише з прапорцем: кому бракує SPK чи OPK, тому й шлемо.
        scope.launch {
            while (isActive) {
                if (RelayFs.Flag.enabled) {
                    for (peer in container.peers.peers.value) {
                        if (peer.dhPubKey.isBlank()) continue
                        val body = runCatching { container.mesh.relayFsBundleFor(peer.id) }.getOrNull() ?: continue
                        runCatching { container.mesh.send(peer.id, body) }
                    }
                }
                withTimeoutOrNull(BUNDLE_TICK_MS) { wake.receive() }
            }
        }
    }

    private companion object {
        const val FILE_NAME = "phantom_relay_fs"
        const val KEY = "keys"
        /** Прапорець — не секрет, тож у звичайних налаштуваннях, окремо від ключів. */
        const val FLAG_FILE = "phantom_relay_fs_flag"
        const val FLAG_KEY = "enabled"
        /** Пачку шлемо лише коли потрібна (RelayFsKeys.bundleFor), тож частий хід нічого не коштує. */
        const val BUNDLE_TICK_MS = 15 * 60_000L
    }
}
