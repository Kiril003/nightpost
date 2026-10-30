package local.phantom.companion.ui.messenger

/**
 * ХТО З АВТОРІВ У ЖУРНАЛІ — НЕ ЛЮДИНА.
 *
 * Кадр екрана блокування 11.09: «peer·phan ⚡ Аналіз запиту: «D»…». PHANTOM і
 * агенти Колегії кладуть свої відповіді в той самий журнал розмов, що й
 * співрозмовники, бо поверхня в них спільна. Автор у них при цьому не відбиток
 * ключа, а місцеве імʼя — і саме цим вони відрізнимі, не вгадуванням по тексту.
 *
 * Перелік один на застосунок: другий перелік у другому місці розійшовся б із
 * цим уже наступного разу, коли зʼявиться новий агент.
 */
object LocalAuthors {

    private val PREFIXES = listOf("peer_phantom", "agent_", "agency_")

    fun isLocal(authorId: String): Boolean =
        PREFIXES.any { authorId.startsWith(it) }

    fun isHuman(authorId: String): Boolean = !isLocal(authorId)
}
