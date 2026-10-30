/**
 * Модалки кореня месенджера, зібрані в ОДИН лінивий шматок.
 *
 * ── ЧОМУ ОДИН МОДУЛЬ, А НЕ СІМ `import()` ─────────────────────────────────
 *
 * Кожен окремий динамічний імпорт народжує rollup-у окремий шматок. Так у
 * цьому дереві вже було: `ModalHost` тримав 57 показових модалок за
 * `lazy(() => import(...))`, і телефон діставав 57 файлів. Тому тут усі
 * сім `lazy()` у `MessengerRoot` вказують на ЦЕЙ модуль — шматок виходить
 * один, і він качається на першому ж відкритті будь-якої з них.
 *
 * ── ЩО СЮДИ МОЖНА, А ЩО НІ ───────────────────────────────────────────────
 *
 * Лише те, що імпортує САМЕ `MessengerRoot` і ніхто більше. Модалка, яку
 * поруч статично тягне `ChatArea` чи `Sidebar` (а вони лишаються в першому
 * кадрі), від лінивої межі не полегшає ані на байт: граф уже має на неї
 * статичне ребро. Станом на 03.10 така одна в самому `MessengerRoot` —
 * `LocationSheetModal`; подробиці листа, видалення й вибір реакції монтує
 * лише `ChatArea` (другий їхній монтаж у корені видалено 03.10 разом із
 * дверима стору, яких не відчиняв жоден дотик).
 *
 * ── ЩО ЗВІДСИ ВИДАЛЕНО 03.10 І ЧОМУ ───────────────────────────────────────
 *
 * Чотири модалки без жодних дверей, що їхали в телефон цим самим шматком:
 * «Дії» (`ActionHubModal`, обидві кнопки в шапці й композері давно
 * відкинуто), мережа P2P з «Ручним SDP» (`P2PNetworkModal`), розумні теки
 * (`SmartFolderModal` — список тек живе в `folderStore`) і «Запланувати»
 * (`ScheduleMessageModal` — час, який не читав жоден диспетчер). Відкладені
 * листи тепер створює сама шухляда `ScheduledMessagesDrawer`.
 *
 * `CallOverlay` теж не тут: дзвінок приходить ззовні й мусить намалюватись
 * тим самим кадром, а не після завантаження шматка.
 */
export { CreateChatModal } from '../CreateChatModal';
export { ForwardMessageModal } from '../ForwardMessageModal';
export { GroupProfileSheet } from '../GroupProfileSheet';
export { MediaLightboxModal } from '../MediaLightboxModal';
export { ScheduledMessagesDrawer } from '../ScheduledMessagesDrawer';
import type React from 'react';
import { PhoneSettingsSheet, type PhoneSettingsProps } from '../PhoneSettingsSheet';
/**
 * Налаштування — один «Профіль» на телефоні й на ПК (дизайн-система §10.9).
 * Настільна модалка з hex-палітрою, вибором акценту, «P2P / Серверною
 * архітектурою» й ключами ШІ-провайдерів пішла: системні налаштування ПК
 * живуть в оболонці (DeskSettings), а месенджер говорить мовою людей.
 */
export const SettingsModal: React.FC<PhoneSettingsProps> = PhoneSettingsSheet;
export { UserProfileModal } from '../UserProfileModal';
/**
 * «Без інтернету» — аркуш, який домівка PHANTOM відкриває з картки звʼязку.
 * Той самий модуль уже лежить тут (через `PhoneSettingsSheet`), тож окремого
 * шматка він не народжує.
 */
export { OfflineSetup } from '../OfflineSetup';
