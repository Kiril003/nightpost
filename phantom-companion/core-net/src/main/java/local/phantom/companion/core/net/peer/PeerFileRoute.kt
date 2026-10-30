package local.phantom.companion.core.net.peer

/**
 * Що вільно віддати вебу з диска.
 *
 * Перехоплювач у `WebMessengerHost` тримає єдиний білий перелік походжень, і
 * доти єдине, що він умів віддавати, — це файли бандла з `assets`. Байти
 * вкладення лежать не там, а в каталозі застосунку, тож у перехоплювача
 * зʼявився другий рід відповіді — і разом з ним місце, де помилка в розборі
 * шляху означає віддати чуже.
 *
 * Тому рішення живе тут, окремо від `WebView`: його видно на очі й видно
 * тестом без апарата. Правило одне й воно біле — розпізнається РІВНО
 * `_files/<blob>`, де `<blob>` уже перевірений [PeerFile.isBlobId], тобто
 * рівно 32 шістнадцяткові цифри. У такому рядку не буває ані `..`, ані
 * скісної, ані крапки: обхід каталогу тут неможливий не тому, що його
 * відсіяли, а тому, що вцілілий рядок не вміє його виразити.
 */
object PeerFileRoute {

    /** Підкаталог бандла, якого в `assets` немає й не буде. */
    const val PREFIX = "_files/"

    /** Каталог вкладень усередині `filesDir`. */
    const val DIR = "msgr"

    /**
     * Ідентифікатор блоба з шляху всередині нашого origin, або null.
     *
     * [rel] — те, що лишилось після `https://phantom.local/webmsgr/`. Усе, що
     * не є рівно одним ідентифікатором після префікса, — не наша справа:
     * вкладений шлях, порожнеча, зайвий хвіст, чужа абетка.
     */
    fun blobOf(rel: String): String? {
        if (!rel.startsWith(PREFIX)) return null
        val tail = rel.removePrefix(PREFIX)
        if (tail.isEmpty() || '/' in tail) return null
        return tail.takeIf { PeerFile.isBlobId(it) }
    }

    fun isFileRequest(rel: String): Boolean = rel.startsWith(PREFIX)

    /**
     * Шлях до байтів у сховищі: `msgr/<розмова>/<блоб>`.
     *
     * Розмова в шляху є, хоч у запиті веба її немає — веб знає лише
     * ідентифікатор блоба. Каталог на розмову лишається, бо стирання розмови
     * мусить бути стиранням каталогу, а не полюванням на рядки; знаходить
     * блоб перебір каталогів, і його дешевизна тут не випадкова: розмов
     * десятки, а індекс, який довелось би тримати збіжним, — ще одне місце,
     * де правда розходиться з диском.
     */
    fun relativePath(conversationId: String, blobId: String): String? {
        if (!PeerFile.isBlobId(blobId)) return null
        if (!isConversationId(conversationId)) return null
        return "$DIR/$conversationId/$blobId"
    }

    /**
     * Ідентифікатори людей — відбитки ключів; сюди не пускаємо нічого іншого.
     * Той самий засув, що й на блобі: назва каталогу теж приходить іззовні.
     */
    fun isConversationId(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_CONVERSATION_CHARS &&
            text.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private const val MAX_CONVERSATION_CHARS = 128
}
