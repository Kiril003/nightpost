"""Text the server sends to people, in the language of the request.

uk, en and cs are complete catalogues. The wave locales (es, pt-BR, de, fr, pl, tr,
it, id) and any unknown tag answer in English until their catalogues exist.
"""
from __future__ import annotations

import contextvars
from typing import Optional

LANG_HEADER = "x-nightpost-lang"
CATALOGUE_LANGS = ("uk", "en", "cs")
WAVE_LANGS = ("es", "pt-BR", "de", "fr", "pl", "tr", "it", "id")
DEFAULT_LANG = "uk"  # no header: existing clients and tests keep today's Ukrainian

_current: contextvars.ContextVar[str] = contextvars.ContextVar("nightpost_lang", default=DEFAULT_LANG)


def normalise(tag: Optional[str]) -> Optional[str]:
    if not tag:
        return None
    base = tag.split(";", 1)[0].strip().replace("_", "-").lower()
    if not base or base == "*":
        return None
    primary = base.split("-", 1)[0]
    if primary == "pt":
        return "pt-BR"
    if primary == "in":
        return "id"
    return primary


def from_headers(lang_header: Optional[str], accept_language: Optional[str]) -> str:
    found = normalise(lang_header)
    if found:
        return found
    for part in (accept_language or "").split(","):
        found = normalise(part)
        if found:
            return found
    return DEFAULT_LANG


def catalogue_lang(lang: Optional[str]) -> str:
    tag = normalise(lang) if lang else None
    return tag if tag in CATALOGUE_LANGS else "en"


def set_lang(lang: str) -> contextvars.Token:
    return _current.set(lang)


def reset_lang(token: contextvars.Token) -> None:
    _current.reset(token)


def current_lang() -> str:
    return _current.get()


def t(key: str, /, lang: Optional[str] = None, **vars) -> str:
    text = CATALOGUES[catalogue_lang(lang or _current.get())][key]
    return text.format(**vars) if vars else text


def refusal_fields(entry, lang: Optional[str] = None) -> dict[str, str]:
    """`entry` is a catalogue key, or a (uk, en) pair for texts that only exist in those two."""
    if isinstance(entry, str):
        return {"message_uk": t(entry, "uk"), "message_en": t(entry, "en"), "message": t(entry, lang)}
    uk, en = entry
    return {"message_uk": uk, "message_en": en, "message": uk if (lang or _current.get()) == "uk" else en}


# key: (uk, en, cs)
_CAT: dict[str, tuple[str, str, str]] = {
    # shared API errors
    "api.too_many_requests": ("Забагато запитів", "Too many requests.", "Příliš mnoho požadavků."),
    "api.device_signature": ("підпис пристрою не зійшовся", "The device signature does not match.", "Podpis zařízení nesouhlasí."),
    "api.stale_proof": (
        "заява не свіжа: годинник розійшовся більш ніж на 2 хвилини",
        "The request is stale: the clock is off by more than 2 minutes.",
        "Žádost je zastaralá: hodiny se liší o více než 2 minuty.",
    ),
    "api.proof_incomplete": ("заява неповна", "The request is incomplete.", "Žádost je neúplná."),
    "api.mailbox_tag": ("Адреса скриньки не тієї форми", "The mailbox address is malformed.", "Adresa schránky má špatný tvar."),
    "api.envelope_length": (
        "Довжина конверта не з переліку",
        "The envelope length is not one of the allowed sizes.",
        "Délka obálky není mezi povolenými velikostmi.",
    ),
    "api.relay_full": ("Сховок повний", "The relay is full.", "Úložiště je plné."),
    "api.exact_tags": (
        "Потрібно рівно {n} різних адрес",
        "Exactly {n} distinct addresses are required.",
        "Je potřeba přesně {n} různých adres.",
    ),
    "api.nothing_to_burn": ("Нічого палити", "Nothing to burn.", "Není co smazat."),
    "api.no_such_entry": ("Немає такого запису", "No such entry.", "Takový záznam neexistuje."),
    "api.no_such_area": ("Немає такої області", "No such area.", "Taková oblast neexistuje."),
    "api.no_such_cell": ("Немає такої комірки", "No such cell.", "Taková buňka neexistuje."),
    "api.bad_cell_kind": ("Комірка чи вид не тієї форми", "The cell or kind is malformed.", "Buňka nebo druh mají špatný tvar."),
    "api.directory_closed": (
        "Довідник ще не відкрито на цьому сервері",
        "The directory is not open on this server.",
        "Adresář na tomto serveru ještě není otevřen.",
    ),
    "api.no_such_venue": ("Немає такого закладу", "No such venue.", "Takové místo neexistuje."),
    "api.not_your_venue": ("Це не ваш заклад", "This is not your venue.", "Toto není vaše místo."),
    "api.h3_area_required": ("Потрібна область H3 r6", "An H3 r6 area is required.", "Je potřeba oblast H3 r6."),
    # directory
    "directory.expiry_past": ("Строк уже минув", "The expiry is already in the past.", "Platnost už vypršela."),
    "directory.expiry_too_long": (
        "Строк довший за {days} діб",
        "The expiry exceeds {days} days.",
        "Platnost je delší než {days} dní.",
    ),
    "directory.handle_hash": (
        "Адреса запису не тієї форми: 64 шістнадцяткові",
        "The handle hash is malformed: 64 hexadecimal characters.",
        "Adresa záznamu má špatný tvar: 64 hexadecimálních znaků.",
    ),
    "directory.invite_malformed": ("Запрошення не читається", "The invite is malformed.", "Pozvánku nelze přečíst."),
    "directory.invite_has_host": (
        "Запрошення мусить бути без адреси в мережі",
        "The invite must carry no host and no port.",
        "Pozvánka nesmí obsahovat síťovou adresu ani port.",
    ),
    "directory.signature": ("Підпис не сходиться", "The signature does not verify.", "Podpis se neshoduje."),
    "directory.handle_taken": ("Ім'я вже зайняте", "The handle is taken.", "Jméno je už obsazené."),
    # attachments
    "attachments.size_unknown": ("Вага файла невідома — надсилати нічого", "The file size is unknown, so there is nothing to send.", "Velikost souboru není známa, není co odeslat."),
    "attachments.empty": ("Файл порожній — везти нічого", "The file is empty, so there is nothing to carry.", "Soubor je prázdný, není co přenášet."),
    "attachments.too_big": (
        "Файл важить {gib} ГіБ при стелі {cap} ГіБ — сховище його не візьме",
        "The file weighs {gib} GiB with a ceiling of {cap} GiB, so storage will not take it.",
        "Soubor má {gib} GiB při stropu {cap} GiB, úložiště ho nepřijme.",
    ),
    "attachments.nonce_missing": ("заява без разового числа", "The request has no one-time number.", "Žádost nemá jednorázové číslo."),
    "attachments.nonce_used": ("цю заяву вже використано", "This request has already been used.", "Tato žádost už byla použita."),
    "attachments.quota_files": (
        "Цей пристрій надіслав {n} файлів за добу при межі {cap} — далі можна буде завтра",
        "This device sent {n} files in a day against a limit of {cap}. Try again tomorrow.",
        "Toto zařízení odeslalo za den {n} souborů při limitu {cap}. Zkuste to zítra.",
    ),
    "attachments.quota_bytes": (
        "Цей пристрій надіслав {used} ГіБ за добу при межі {cap} ГіБ — далі можна буде завтра",
        "This device sent {used} GiB in a day against a limit of {cap} GiB. Try again tomorrow.",
        "Toto zařízení odeslalo za den {used} GiB při limitu {cap} GiB. Zkuste to zítra.",
    ),
    "attachments.sees.1": ("який пристрій поклав файл — його відкритий ключ", "which device uploaded the file, by its public key", "které zařízení soubor nahrálo, podle jeho veřejného klíče"),
    "attachments.sees.2": ("коли це сталося", "when it happened", "kdy k tomu došlo"),
    "attachments.sees.3": ("скільки важить файл", "how big the file is", "jak je soubor velký"),
    "attachments.sees.4": ("скільки файлів цей пристрій надіслав за добу", "how many files this device sent in a day", "kolik souborů toto zařízení odeslalo za den"),
    "attachments.hidden.1": ("що всередині файла — на сервер їде лише зашифроване", "what is inside the file; only encrypted bytes reach the server", "co je uvnitř souboru; na server jdou jen zašifrovaná data"),
    "attachments.hidden.2": ("ключ розшифрування: він їде прямо співрозмовнику, повз сервер", "the decryption key, which goes straight to the other person and bypasses the server", "dešifrovací klíč, který jde přímo druhé osobě mimo server"),
    "attachments.hidden.3": ("ім'я файла й підпис до нього", "the file name and its caption", "název souboru a popisek k němu"),
    "attachments.hidden.4": ("кому ви його надіслали", "who you sent it to", "komu jste ho poslali"),
    "attachments.plain": (
        "Файл шифрується на вашому пристрої. Сервер тримає зашифровані байти й не має ключа до них, тож прочитати файл не може. Але він бачить, що файл існує: який пристрій його поклав, коли і якої він ваги. Кому ви його надіслали — не бачить.",
        "The file is encrypted on your device. The server holds encrypted bytes and has no key to them, so it cannot read the file. It does see that the file exists: which device uploaded it, when, and how big it is. It does not see who you sent it to.",
        "Soubor se šifruje ve vašem zařízení. Server drží zašifrovaná data a nemá k nim klíč, takže soubor přečíst nemůže. Vidí ale, že soubor existuje: které zařízení ho nahrálo, kdy a jak je velký. Komu jste ho poslali, nevidí.",
    ),
    # points
    "points.not_configured": ("Бали ще не підключено", "Points are not configured.", "Body ještě nejsou zapojeny."),
    "points.venues_not_wired": ("Заклади ще не підключено", "Venues are not wired.", "Místa ještě nejsou zapojena."),
    "points.badges_not_wired": ("Знаки ще не підключено", "Badges are not wired.", "Odznaky ještě nejsou zapojeny."),
    "points.tx_malformed": (
        "Підпис транзакції чи адреса не тієї форми",
        "The transaction signature or address is malformed.",
        "Podpis transakce nebo adresa mají špatný tvar.",
    ),
    "points.wallet_signature": ("підпис гаманця не зійшовся", "The wallet signature does not match.", "Podpis peněženky nesouhlasí."),
    "points.refusal.not_at_venue": ("Ви не в комірці цього закладу.", "You are not in this venue's cell.", "Nejste v buňce tohoto místa."),
    "points.refusal.outside_window": (
        "Відмітитись можна за 2 години до й після часу зустрічі.",
        "Check-in opens 2 hours before and closes 2 hours after the meetup time.",
        "Check-in jde provést 2 hodiny před časem setkání a 2 hodiny po něm.",
    ),
    "points.refusal.venue_unknown": ("Такого закладу немає.", "No such venue.", "Takové místo neexistuje."),
    "points.refusal.tx_failed": ("Мережа відхилила цю транзакцію.", "The network rejected this transaction.", "Síť tuto transakci odmítla."),
    "points.refusal.not_your_payment": ("Цю транзакцію підписав не ваш гаманець.", "This transaction was not signed by your wallet.", "Tuto transakci nepodepsala vaše peněženka."),
    "points.refusal.not_a_payment": ("З гаманця в цій транзакції нічого не пішло.", "Nothing left your wallet in this transaction.", "V této transakci z peněženky nic neodešlo."),
    "points.refusal.not_this_bill": ("Ця транзакція не позначена цим рахунком.", "This transaction is not marked with this bill.", "Tato transakce není označena tímto účtem."),
    "points.refusal.too_old": ("Транзакція старша за тиждень.", "The transaction is older than a week.", "Transakce je starší než týden."),
    "points.refusal.tx_used": ("Цю транзакцію вже зараховано.", "This transaction has already been counted.", "Tato transakce už byla započtena."),
    "points.refusal.wallet_taken": ("Цей гаманець уже прив'язано до іншого пристрою.", "This wallet is already linked to another device.", "Tato peněženka je už propojena s jiným zařízením."),
    "points.refusal.wallet_not_linked": ("Спершу оплатіть частку з цього гаманця.", "Pay a share from this wallet first.", "Nejdřív zaplaťte podíl z této peněženky."),
    "points.refusal.badge_invalid": ("Знак Seeker недійсний або прострочений.", "The Seeker badge is invalid or expired.", "Odznak Seeker je neplatný nebo vypršel."),
    "points.refusal.network_unavailable": (
        "Мережа Solana зараз не відповіла. Спробуйте за хвилину.",
        "The Solana network did not answer. Try again in a minute.",
        "Síť Solana neodpověděla. Zkuste to za minutu.",
    ),
    # venues
    "venues.refusal.bad_version": ("Невідома версія сторінки.", "Unknown page version.", "Neznámá verze stránky."),
    "venues.refusal.too_big": ("Сторінка завелика.", "The page is too big.", "Stránka je příliš velká."),
    "venues.refusal.not_a_list": ("Блоки мають бути списком.", "Blocks must be a list.", "Bloky musí být seznam."),
    "venues.refusal.too_many_blocks": ("Забагато блоків.", "Too many blocks.", "Příliš mnoho bloků."),
    "venues.refusal.unknown_block": ("Такого блоку немає в словнику.", "This block is not in the vocabulary.", "Takový blok ve slovníku není."),
    "venues.refusal.unknown_field": ("Такого поля в блоці немає.", "This field is not part of the block.", "Toto pole v bloku není."),
    "venues.refusal.missing_field": ("Бракує обов'язкового поля.", "A required field is missing.", "Chybí povinné pole."),
    "venues.refusal.duplicate_block": ("Цей блок може бути лише один.", "Only one block of this kind is allowed.", "Tento blok smí být jen jeden."),
    "venues.refusal.bad_text": (
        "Текст порожній, задовгий або з керівними чи невидимими символами.",
        "Text is empty, too long or has control or invisible characters.",
        "Text je prázdný, příliš dlouhý nebo obsahuje řídicí či neviditelné znaky.",
    ),
    "venues.refusal.bad_point": ("Точка поза межами Землі.", "The point is not on Earth.", "Bod leží mimo Zemi."),
    "venues.refusal.bad_hours": ("Години мають вигляд «пн 08:00–22:00».", "Hours must look like “mon 08:00–22:00”.", "Hodiny musí vypadat jako „po 08:00–22:00“."),
    "venues.refusal.bad_token": ("Валюта меню — USDC або SOL.", "Menu currency must be USDC or SOL.", "Měna menu je USDC nebo SOL."),
    "venues.refusal.mixed_tokens": ("Усе меню — в одній валюті.", "The whole menu must use one currency.", "Celé menu musí být v jedné měně."),
    "venues.refusal.bad_item_id": (
        "Код страви — латиниця, цифри, «-» чи «_», до 36 знаків.",
        "A dish id is Latin letters, digits, “-” or “_”, up to 36 characters.",
        "Kód jídla tvoří latinka, číslice, „-“ nebo „_“, nejvýše 36 znaků.",
    ),
    "venues.refusal.duplicate_item": ("Дві страви з однаковим кодом.", "Two dishes share one id.", "Dvě jídla mají stejný kód."),
    "venues.refusal.too_many_items": ("Забагато страв.", "Too many dishes.", "Příliš mnoho jídel."),
    "venues.refusal.bad_price": (
        "Ціна — додатне число рядком, з крапкою, не більше знаків, ніж у валюти.",
        "A price is a positive decimal string with a dot and no more decimals than the currency has.",
        "Cena je kladné číslo zapsané textem s tečkou a nejvýše tolika desetinnými místy, kolik má měna.",
    ),
    "venues.refusal.bad_wallet": ("Це не адреса Solana.", "This is not a Solana address.", "Toto není adresa Solana."),
    "venues.refusal.bad_event_time": (
        "Час події має вигляд «РРРР-ММ-ДДTГГ:ХХ», а кінець — після початку.",
        "Event time must look like “YYYY-MM-DDTHH:MM”, with end after start.",
        "Čas události musí mít tvar „RRRR-MM-DDTHH:MM“ a konec musí být po začátku.",
    ),
    "venues.refusal.duplicate_event": ("Дві події з однаковим кодом.", "Two events share one id.", "Dvě události mají stejný kód."),
    "venues.refusal.too_many_events": ("Забагато подій.", "Too many events.", "Příliš mnoho událostí."),
    "venues.refusal.published": ("Спершу зніміть заклад із публікації.", "Unpublish the venue first.", "Nejdřív místo stáhněte z publikace."),
    "venues.refusal.needs_address": (
        "Без адреси й точки на мапі сторінку не опублікувати.",
        "A page needs an address and a map point before it can be published.",
        "Bez adresy a bodu na mapě stránku nelze publikovat.",
    ),
    "venues.refusal.too_many_venues": ("Забагато закладів на один акаунт.", "Too many venues for one account.", "Příliš mnoho míst na jeden účet."),
    "venues.refusal.visit_required": (
        "Оцінити заклад можна після візиту за останні 30 днів.",
        "You can rate a venue after visiting it in the last 30 days.",
        "Místo lze ohodnotit po návštěvě za posledních 30 dní.",
    ),
    "venues.refusal.bad_schedule": (
        "Немає чернетки, або час уже минув чи далі ніж за рік.",
        "No draft, or the time is past or more than a year ahead.",
        "Není koncept, nebo je čas už minulý či dál než za rok.",
    ),
    "venues.refusal.no_such_block": ("На цій сторінці немає такого запису.", "This page has no such booking or message block.", "Na této stránce takový záznam není."),
    "venues.refusal.bad_slot": ("Цей час не підходить: виберіть інший у межах запису.", "That time does not fit this booking. Pick another.", "Tento čas nesedí: vyberte jiný v rámci rezervace."),
    "venues.refusal.slot_taken": ("Цей час уже зайнятий.", "That time is already taken.", "Tento čas je už obsazený."),
    "venues.refusal.bad_service": ("Такої послуги на сторінці немає.", "This page has no such service.", "Taková služba na stránce není."),
    "venues.refusal.bad_staff": ("Такого майстра на сторінці немає.", "This page has no such person to book with.", "Takový pracovník na stránce není."),
    "venues.refusal.bad_contact": ("Залиште ім'я і контакт, щоб вам відповіли.", "Leave your name and a contact so they can reply.", "Zanechte jméno a kontakt, aby vám mohli odpovědět."),
    "venues.refusal.messages_off": ("Власник сторінки зараз не приймає повідомлень.", "The page owner is not taking messages now.", "Majitel stránky teď nepřijímá zprávy."),
    "venues.refusal.empty_cart": ("Кошик порожній.", "The cart is empty.", "Košík je prázdný."),
    "venues.refusal.bad_item": ("Такого товару на сторінці немає.", "This page has no such item.", "Takové zboží na stránce není."),
    "venues.refusal.out_of_stock": ("Стільки цього товару вже немає.", "Not that many of this item are left.", "Tolik kusů už nezbývá."),
    "venues.refusal.bad_fulfil": ("Виберіть самовивіз або доставку.", "Pick pickup or delivery.", "Vyberte osobní odběr, nebo doručení."),
    "venues.refusal.no_delivery": ("Ця сторінка не доставляє.", "This page does not deliver.", "Tato stránka nedoručuje."),
    "venues.refusal.no_pickup": ("Самовивозу тут немає, лише доставка.", "No pickup here, delivery only.", "Osobní odběr tu není, jen doručení."),
    "venues.refusal.bad_zone": ("Туди доставки немає.", "No delivery to that area.", "Tam se nedoručuje."),
    "venues.refusal.no_address": ("Вкажіть адресу доставки.", "Add a delivery address.", "Zadejte doručovací adresu."),
    "venues.refusal.below_min": ("Замало для доставки в цю зону.", "Below the minimum order for that area.", "Pod minimální objednávkou pro tuto oblast."),
    "venues.refusal.enroll_off": ("На цей курс зараз не записують.", "This course is not taking people now.", "Do tohoto kurzu se teď nezapisuje."),
    "venues.refusal.course_over": ("Цей курс уже закінчився.", "This course has ended.", "Tento kurz už skončil."),
    "venues.refusal.course_full": ("Місць на курсі вже немає.", "This course is full.", "Kurz je plný."),
    "venues.refusal.already_enrolled": ("Ви вже записані на цей курс.", "You are already on this course.", "Do tohoto kurzu už jste zapsaní."),
    "venues.refusal.inbox_full": ("Власник ще не розібрав попередні запити. Спробуйте пізніше.", "The owner has not caught up yet. Try later.", "Majitel ještě nevyřídil předchozí žádosti. Zkuste to později."),
    "venues.refusal.bad_kind": ("Оберіть, що це за сторінка.", "Pick what kind of page this is.", "Vyberte, o jakou stránku jde."),
    "venues.refusal.no_input": ("Опишіть свою справу або додайте прайс.", "Describe your business or add a price list.", "Popište svou činnost nebo přidejte ceník."),
    "venues.refusal.bad_table": ("Прайс не читається. Підійде CSV або XLSX.", "The price list can't be read. Use CSV or XLSX.", "Ceník nejde přečíst. Použijte CSV nebo XLSX."),
    "venues.refusal.table_too_big": ("Прайс завеликий: до 512 КБ.", "The price list is too big: up to 512 KB.", "Ceník je příliš velký: nejvýš 512 KB."),
    "api.no_such_catalogue": ("Немає такого каталогу", "No such catalogue.", "Takový katalog neexistuje."),
    "venues.refusal.demo_place": (
        "Це місце із шаблону. Поставте на мапі своє.",
        "This place comes from the template. Set your own on the map.",
        "Toto místo je ze šablony. Umístěte na mapu své vlastní.",
    ),
    "venues.refusal.sample_content": (
        "У блоці ще текст або фото із шаблону. Замініть їх своїми.",
        "This block still holds the template's words or photos. Replace them with your own.",
        "V bloku je ještě text nebo fotka ze šablony. Nahraďte je svými.",
    ),
    # venue claims
    "venue_claims.refusal.osm_not_found": ("Такого місця на мапі OpenStreetMap не знайдено.", "No such place was found on OpenStreetMap.", "Takové místo se na OpenStreetMap nenašlo."),
    "venue_claims.refusal.osm_unnamed": ("У цього обʼєкта на мапі немає назви.", "This map object has no name.", "Tento objekt na mapě nemá název."),
    "venue_claims.refusal.osm_not_a_place": ("Це не заклад і не місце на мапі.", "This map object is not a venue or place.", "Toto není podnik ani místo na mapě."),
    "venue_claims.refusal.too_far": ("Обʼєкт на мапі далі ніж за 150 м від адреси закладу.", "The map object is more than 150 m from the venue address.", "Objekt na mapě je dál než 150 m od adresy místa."),
    "venue_claims.refusal.needs_address": ("Спершу вкажіть адресу й точку закладу на сторінці.", "Set the venue address and map point on the page first.", "Nejdřív na stránce zadejte adresu a bod místa."),
    "venue_claims.refusal.venue_has_claim": ("Для цього закладу вже є активна заявка або підтвердження.", "This venue already has an active or approved claim.", "Toto místo už má aktivní nebo schválenou žádost."),
    "venue_claims.refusal.already_claimed": ("Це місце вже закріплено за закладом.", "This place is already claimed by a venue.", "Toto místo už je přiřazeno podniku."),
    "venue_claims.refusal.osm_unavailable": ("Сервер мапи OpenStreetMap зараз не відповів. Спробуйте за хвилину.", "OpenStreetMap did not answer right now. Try again in a minute.", "OpenStreetMap neodpověděl. Zkuste to za minutu."),
    "venue_claims.refusal.not_pending": ("Ця заявка вже розглянута або відкликана.", "This claim has already been decided or withdrawn.", "Tato žádost už byla vyřízena nebo stažena."),
    "venue_claims.refusal.no_such_event": ("Такої події немає.", "No such event.", "Taková událost neexistuje."),
    "venue_claims.refusal.event_cancelled": ("Цю подію скасовано.", "This event has been cancelled.", "Tato událost byla zrušena."),
    "venue_claims.refusal.event_full": ("На цю подію більше немає вільних місць.", "This event has reached its capacity.", "Na tuto událost už nejsou volná místa."),
    "venue_claims.refusal.event_over": ("Ця подія вже минула.", "This event is already over.", "Tato událost už skončila."),
    "venue_claims.refusal.own_claim": ("Власну заявку підтвердити не можна: її розгляне інший модератор.", "You cannot approve your own claim; another moderator has to.", "Vlastní žádost schválit nelze, posoudí ji jiný moderátor."),
    "venue_claims.refusal.bad_text": ("Текст містить неприпустимі символи або задовгий.", "Text contains invalid characters or is too long.", "Text obsahuje nepřípustné znaky nebo je příliš dlouhý."),
    # venue editor
    "venue_editor.no_image": ("Немає такого зображення", "No such image.", "Takový obrázek neexistuje."),
    "venue_editor.no_version": ("Немає такої версії", "No such version.", "Taková verze neexistuje."),
    "venue_editor.image_not_stored": ("Зображення не збереглося", "The image was not stored.", "Obrázek se neuložil."),
    # emails
    "mailer.recovery.subject": ("Nightpost — код відновлення", "Nightpost — recovery code", "Nightpost — kód pro obnovení"),
    "mailer.recovery.body": (
        "Код для відновлення ключа: {code}\n\nВін дійсний 15 хвилин. Якщо ви цього не просили — просто зітріть листа, ваш ключ лишиться чинним.",
        "Your key recovery code: {code}\n\nIt is valid for 15 minutes. If you did not ask for it, just delete this email; your key stays valid.",
        "Kód pro obnovení klíče: {code}\n\nPlatí 15 minut. Pokud jste o něj nežádali, e-mail prostě smažte, váš klíč zůstane platný.",
    ),
    "mailer.login.subject": ("Nightpost — код входу", "Nightpost — sign-in code", "Nightpost — kód pro přihlášení"),
    "mailer.login.body": (
        "Код для входу в кабінет: {code}\n\nВін дійсний 15 хвилин і потрібен лише один раз. Якщо ви цього не просили — просто зітріть листа.",
        "Your sign-in code for the account page: {code}\n\nIt is valid for 15 minutes and works once. If you did not ask for it, just delete this email.",
        "Kód pro přihlášení do účtu: {code}\n\nPlatí 15 minut a použít ho lze jen jednou. Pokud jste o něj nežádali, e-mail prostě smažte.",
    ),
    "mailer.beta.subject": ("Nightpost — ваш бета-ключ", "Nightpost — your beta key", "Nightpost — váš beta klíč"),
    "mailer.beta.body": (
        "Це ваш ключ Nightpost рівня «Особистий» на час бети:\n\n{key}\n\n"
        "Щоб активувати: у застосунку на телефоні відкрийте Налаштування → «про Nightpost» → «Ліцензія», введіть ключ у поле «Ключ ліцензії» й натисніть «Активувати».\n\n"
        "Поспішати нікуди: перші {days} днів після встановлення телефон відкриває все й без ключа. Ключ потрібен, щоб права лишились після проби.\n\n"
        "Збережіть цього листа: у нашій базі лежить лише відбиток ключа, тож надіслати його ще раз ми не зможемо — лише видати новий у кабінеті.",
        "This is your Nightpost key for the Personal tier during the beta:\n\n{key}\n\n"
        "To activate it: in the phone app open Settings → About Nightpost → Licence, enter the key in the Licence key field and tap Activate.\n\n"
        "No rush: for the first {days} days after installing, the phone opens everything without a key. The key keeps your rights after the trial.\n\n"
        "Keep this email: our database stores only a fingerprint of the key, so we cannot send it again. We can only issue a new one in your account.",
        "Toto je váš klíč Nightpost úrovně Osobní na dobu bety:\n\n{key}\n\n"
        "Aktivace: v aplikaci v telefonu otevřete Nastavení → O Nightpost → Licence, zadejte klíč do pole Licenční klíč a klepněte na Aktivovat.\n\n"
        "Nikam nespěchejte: prvních {days} dní po instalaci telefon otevře vše i bez klíče. Klíč je potřeba, aby vám práva zůstala i po zkušební době.\n\n"
        "Tento e-mail si uložte: v naší databázi je jen otisk klíče, takže ho nemůžeme poslat znovu, jen vydat nový ve vašem účtu.",
    ),
    "mailer.new_keys.subject": ("Nightpost — новий ключ", "Nightpost — new key", "Nightpost — nový klíč"),
    "mailer.new_keys.body": (
        "Ваш попередній ключ більше не діє. Новий:\n\n{keys}\n\nВведіть його в застосунку. Пристрої, активовані раніше, лишились на місці.",
        "Your previous key no longer works. The new one:\n\n{keys}\n\nEnter it in the app. Devices activated earlier stay as they are.",
        "Váš předchozí klíč už neplatí. Nový:\n\n{keys}\n\nZadejte ho v aplikaci. Dříve aktivovaná zařízení zůstávají beze změny.",
    ),
}

CATALOGUES: dict[str, dict[str, str]] = {
    "uk": {k: v[0] for k, v in _CAT.items()},
    "en": {k: v[1] for k, v in _CAT.items()},
    "cs": {k: v[2] for k, v in _CAT.items()},
}


class LangMiddleware:
    """Pure ASGI so the language is set in the same context the handler and background tasks run in."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        headers = {k: v for k, v in scope.get("headers", ())}
        lang = from_headers(
            headers.get(LANG_HEADER.encode(), b"").decode("latin-1"),
            headers.get(b"accept-language", b"").decode("latin-1"),
        )
        token = set_lang(lang)
        try:
            await self.app(scope, receive, send)
        finally:
            reset_lang(token)
