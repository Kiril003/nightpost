import type { Lang } from './types';

/**
 * Огляд, пристрої й покупки в кабінеті.
 *
 * Кожне число тут приходить із `/api/me` або `/api/me/sessions` — кабінет
 * нічого не рахує «на око» й нічого не домальовує. Коли поля немає (пристрій
 * ще не звірявся, сервер старий і не віддає джерело ліцензії), текст так і
 * каже, замість правдоподібної заглушки.
 */
export interface CabinetCopy {
  nav: { overview: string; devices: string; purchases: string };
  overview: {
    title: string;
    lead: string;
    licence: string;
    licenceNone: string;
    licenceNoneHint: string;
    licenceRevokedOnly: string;
    devices: string;
    devicesOf: (used: number, max: number) => string;
    devicesNone: string;
    lastSeen: string;
    lastSeenNever: string;
    support: string;
    supportUnread: (n: number) => string;
    supportQuiet: string;
    sessions: string;
    sessionsCount: (n: number) => string;
    sessionsUnknown: string;
    build: string;
    purchasesCount: (n: number) => string;
    buildReady: (version: string) => string;
    buildNone: string;
    open: string;
    nextTitle: string;
    nextLicence: string;
    nextDevice: string;
    nextFine: string;
  };
  /** Стан сервісів — перевіряється з браузера в мить показу. */
  status: {
    title: string;
    lead: string;
    server: string;
    relay: string;
    checking: string;
    ok: (ms: number) => string;
    down: (code: number) => string;
    unreachable: string;
    notExposed: string;
    recheck: string;
  };
  devices: {
    title: string;
    lead: string;
    empty: string;
    emptyHint: string;
    activated: string;
    lastSeen: string;
    never: string;
    neverHint: string;
    licence: string;
    release: string;
    releasing: string;
    releaseFailed: string;
    seatsFree: (n: number) => string;
    staleHint: string;
    addTitle: string;
    addSteps: string[];
    playNote: string;
  };
  /** «Що сервер про вас зберігає» — звірено зі схемою бази
   *  (server/tests/test_cabinet_stored_is_the_schema.py). */
  stored: {
    title: string;
    keptTitle: string;
    kept: string[];
    notTitle: string;
    not: string[];
  };
  purchases: {
    title: string;
    lead: string;
    empty: string;
    emptyHint: string;
    columns: { date: string; what: string; source: string; status: string };
    source: Record<string, string>;
    sourceUnknown: string;
    active: string;
    revoked: string;
    reason: Record<string, string>;
    reasonOther: string;
    receipts: string;
    solanaTitle: string;
    solanaBody: string;
    solanaTag: string;
  };
}

const uk: CabinetCopy = {
  nav: { overview: 'Я', devices: 'Пристрої', purchases: 'Покупки' },
  overview: {
    title: 'Огляд',
    lead: 'Головне про ваш кабінет на одному екрані. Усе тут — з нашого сервера в цю мить.',
    licence: 'Ліцензія',
    licenceNone: 'Ліцензії немає',
    licenceNoneHint: 'Вільний рівень працює без неї: листування, мапа, навігація, тривоги.',
    licenceRevokedOnly: 'Усі ліцензії відкликано',
    devices: 'Пристрої',
    devicesOf: (used, max) => `${used} з ${max} місць зайнято`,
    devicesNone: 'Жодного пристрою не активовано',
    lastSeen: 'Останній звʼязок',
    lastSeenNever: 'Пристрої ще не звірялися з сервером',
    support: 'Підтримка',
    supportUnread: (n) => (n === 1 ? '1 нова відповідь' : `Нових відповідей: ${n}`),
    supportQuiet: 'Нових відповідей немає',
    sessions: 'Входи',
    sessionsCount: (n) => (n === 1 ? 'Відкрито 1 сесію' : `Відкрито сесій: ${n}`),
    sessionsUnknown: 'Не вдалося дізнатися',
    build: 'Збірка',
    purchasesCount: (n) => `Записів: ${n}`,
    buildReady: (v) => `Доступна версія ${v}`,
    buildNone: 'Сервер зараз не роздає збірку',
    open: 'Відкрити',
    nextTitle: 'Що далі',
    nextLicence: 'Ліцензія не обовʼязкова. Якщо купили її на іншу пошту — додайте ключ у розділі «Ліцензії».',
    nextDevice: 'Ліцензія є, але жоден пристрій її ще не активував. Введіть ключ у застосунку. Телефон: Профіль → Допомога → «Ваша копія». Компʼютер: Налаштування → Ліцензія.',
    nextFine: 'Усе гаразд: ліцензія діє, пристрої на звʼязку.',
  },
  status: {
    title: 'Стан сервісів',
    lead: 'Перевіряється просто зараз із вашого браузера, а не взято з кешу.',
    server: 'Сервер ліцензій і кабінету',
    relay: 'Сховок листів',
    checking: 'перевіряємо…',
    ok: (ms) => `працює · ${ms} мс`,
    down: (code) => `відповідає помилкою ${code}`,
    unreachable: 'не відповідає',
    notExposed: 'цей сервер не повідомляє стан сховку',
    recheck: 'Перевірити ще раз',
  },
  devices: {
    title: 'Пристрої',
    lead: 'Телефони й компʼютери, на яких активовано ваші ліцензії. Місце можна звільнити одразу — наприклад, якщо телефон загубився.',
    empty: 'Пристроїв поки немає',
    emptyHint: 'Пристрій зʼявиться тут, щойно ви введете ключ ліцензії в застосунку.',
    activated: 'Активовано',
    lastSeen: 'Останній звʼязок',
    never: 'ще не звірявся',
    neverHint: 'Застосунок звіряє ліцензію з сервером щонайбільше раз на 20 годин і лише коли є інтернет. До першої звірки дати немає.',
    licence: 'Ліцензія',
    release: 'Звільнити місце',
    releasing: 'Звільняємо…',
    releaseFailed: 'Не вдалося звільнити місце. Оновіть сторінку й спробуйте ще раз; якщо не вийде — напишіть нам.',
    seatsFree: (n) => (n === 1 ? 'Вільне ще 1 місце' : `Вільних місць: ${n}`),
    staleHint: 'Після звільнення пристрій перестане відкривати платні можливості, коли наступного разу звіриться з сервером. Вільний рівень на ньому лишиться.',
    addTitle: 'Як додати пристрій',
    addSteps: [
      'Встановіть Nightpost: телефон — з розділу «Завантаження» або з Google Play; компʼютер — з «Завантаження».',
      'Відкрийте ліцензію. Телефон: Профіль → Допомога → «Ваша копія». Компʼютер: Налаштування → Ліцензія.',
      'Введіть ключ PHTM-… з листа про покупку й натисніть «Активувати». Купили в Google Play — натисніть «Відновити покупку».',
      'Пристрій зʼявиться в цьому списку. Якщо місця скінчились — спершу звільніть старе.',
    ],
    playNote: 'Ключ від покупки в Google Play активується на телефоні сам, після оплати.',
  },
  stored: {
    title: 'Що сервер про вас зберігає',
    keptTitle: 'Зберігає',
    kept: [
      'Пошту, імʼя для показу, мову, роль, дату створення й останнього входу.',
      'Сесії входу: відбиток токена (сам токен — лише у вашому браузері), коли почалась і скінчиться, IP-адресу.',
      'Код входу — лише його відбиток і 15 хвилин, потім стирається.',
      'Ваші звернення й наші відповіді.',
      'Ліцензії, привʼязані до вашої пошти, і пристрої на них (назва, відбиток, дати).',
    ],
    notTitle: 'Не зберігає',
    not: [
      'Пароль: вхід кодом із пошти або QR із застосунку.',
      'Даних картки — їх тримає той, хто приймав оплату.',
      'Нічого з ваших пристроїв: розмов, мапи, місця, сховища.',
    ],
  },
  purchases: {
    title: 'Покупки',
    lead: 'Кожна ліцензія на вашу пошту — звідки вона, коли зʼявилась і чи діє. Квитанції надсилає той, хто приймав оплату.',
    empty: 'Покупок немає',
    emptyHint: 'Продаж на сайті ще не відкрито. Коли відкриється, кожна покупка зʼявиться тут.',
    columns: { date: 'Дата', what: 'Що', source: 'Звідки', status: 'Стан' },
    source: {
      lemonsqueezy: 'Сайт (Lemon Squeezy)',
      play: 'Google Play',
      beta: 'Бета-доступ',
      manual: 'Видано вручну',
      solana: 'Переказ із гаманця (Solana)',
      seeker: 'Місяць за Seeker',
    },
    sourceUnknown: 'Невідомо',
    active: 'Діє',
    revoked: 'Відкликано',
    reason: {
      refunded: 'гроші повернуто',
      play_voided: 'покупку скасовано в Google Play',
      play_canceled: 'покупку скасовано в Google Play',
      play_expired: 'передплата Google Play закінчилась',
      seeker_month_over: 'місяць за Seeker минув',
    },
    reasonOther: 'відкликано нами — напишіть, якщо незрозуміло чому',
    receipts: 'Квитанцію за покупку на сайті надсилає Lemon Squeezy на вашу пошту, за покупку в Google Play — Google. У нас копії квитанцій немає.',
    solanaTitle: 'Оплата через Solana',
    solanaBody: 'Готуємо. Зараз купити ліцензію через Solana не можна — коли стане можна, ця картка зміниться.',
    solanaTag: 'ще не працює',
  },
};

const en: CabinetCopy = {
  nav: { overview: 'You', devices: 'Devices', purchases: 'Purchases' },
  overview: {
    title: 'Overview',
    lead: 'The essentials of your account on one screen. Everything here comes from our server right now.',
    licence: 'Licence',
    licenceNone: 'No licence',
    licenceNoneHint: 'The free tier works without one: messages, map, navigation, alerts.',
    licenceRevokedOnly: 'All licences are revoked',
    devices: 'Devices',
    devicesOf: (used, max) => `${used} of ${max} seats in use`,
    devicesNone: 'No device activated yet',
    lastSeen: 'Last check-in',
    lastSeenNever: 'No device has checked in with the server yet',
    support: 'Support',
    supportUnread: (n) => (n === 1 ? '1 new reply' : `${n} new replies`),
    supportQuiet: 'No new replies',
    sessions: 'Sign-ins',
    sessionsCount: (n) => (n === 1 ? '1 open session' : `${n} open sessions`),
    sessionsUnknown: 'Could not find out',
    build: 'Build',
    purchasesCount: (n) => (n === 1 ? '1 record' : `${n} records`),
    buildReady: (v) => `Version ${v} is available`,
    buildNone: 'The server is not handing out a build right now',
    open: 'Open',
    nextTitle: 'What next',
    nextLicence: 'A licence is optional. If you bought one with another email, add the key under Licences.',
    nextDevice: 'You have a licence, but no device has activated it yet. Enter the key in the app. Phone: Profile → Help → “Your copy”. Computer: Settings → Licence.',
    nextFine: 'All good: the licence is active and your devices are checking in.',
  },
  status: {
    title: 'Service status',
    lead: 'Checked right now from your browser, not from a cache.',
    server: 'Licence and account server',
    relay: 'Message drop',
    checking: 'checking…',
    ok: (ms) => `up · ${ms} ms`,
    down: (code) => `answers with error ${code}`,
    unreachable: 'not answering',
    notExposed: 'this server does not report drop status',
    recheck: 'Check again',
  },
  devices: {
    title: 'Devices',
    lead: 'The phones and computers your licences are activated on. You can free a seat at once — for example, if a phone is lost.',
    empty: 'No devices yet',
    emptyHint: 'A device appears here as soon as you enter your licence key in the app.',
    activated: 'Activated',
    lastSeen: 'Last check-in',
    never: 'not yet',
    neverHint: 'The app checks the licence with the server at most once every 20 hours, and only with internet. Until the first check there is no date.',
    licence: 'Licence',
    release: 'Free this seat',
    releasing: 'Freeing…',
    releaseFailed: 'Could not free the seat. Refresh the page and try again; if it still fails, write to us.',
    seatsFree: (n) => (n === 1 ? '1 seat free' : `${n} seats free`),
    staleHint: 'Once freed, the device stops opening paid features the next time it checks in with the server. The free tier stays on it.',
    addTitle: 'How to add a device',
    addSteps: [
      'Install Nightpost: phone — from Downloads or Google Play; computer — from Downloads.',
      'Open the licence screen. Phone: Profile → Help → “Your copy”. Computer: Settings → Licence.',
      'Enter the PHTM-… key from your purchase email and tap Activate. Bought on Google Play — tap Restore purchase.',
      'The device appears in this list. If the seats are used up, free an old one first.',
    ],
    playNote: 'A key from a Google Play purchase activates on the phone by itself after payment.',
  },
  stored: {
    title: 'What the server keeps about you',
    keptTitle: 'Keeps',
    kept: [
      'Your email, display name, language, role, creation date and last sign-in.',
      'Sign-in sessions: a fingerprint of the token (the token itself lives only in your browser), when it started and ends, the IP address.',
      'A sign-in code — only its fingerprint and only for 15 minutes, then erased.',
      'Your messages to us and our replies.',
      'Licences tied to your email and the devices on them (name, fingerprint, dates).',
    ],
    notTitle: 'Does not keep',
    not: [
      'A password: sign-in is an emailed code or a QR from the app.',
      'Card details — whoever took the payment keeps them.',
      'Anything from your devices: conversations, map, location, vault.',
    ],
  },
  purchases: {
    title: 'Purchases',
    lead: 'Every licence on your email — where it came from, when, and whether it is active. Receipts are sent by whoever took the payment.',
    empty: 'No purchases',
    emptyHint: 'Sales on the site have not opened yet. When they do, every purchase will appear here.',
    columns: { date: 'Date', what: 'What', source: 'From', status: 'Status' },
    source: {
      lemonsqueezy: 'Website (Lemon Squeezy)',
      play: 'Google Play',
      beta: 'Beta access',
      manual: 'Issued by hand',
      solana: 'Wallet transfer (Solana)',
      seeker: 'Seeker month',
    },
    sourceUnknown: 'Unknown',
    active: 'Active',
    revoked: 'Revoked',
    reason: {
      refunded: 'refunded',
      play_voided: 'purchase cancelled in Google Play',
      play_canceled: 'purchase cancelled in Google Play',
      play_expired: 'Google Play subscription ended',
      seeker_month_over: 'the Seeker month is over',
    },
    reasonOther: 'revoked by us — write to us if the reason is unclear',
    receipts: 'For a purchase on the site, Lemon Squeezy emails the receipt; for Google Play, Google does. We keep no copy of receipts.',
    solanaTitle: 'Paying with Solana',
    solanaBody: 'In preparation. You cannot buy a licence with Solana yet — this card will change when you can.',
    solanaTag: 'not working yet',
  },
};

export const cabinetCopy: Record<Lang, CabinetCopy> = { uk, en };
