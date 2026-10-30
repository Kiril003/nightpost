import type { Lang } from './types';
import type { Role } from '../lib/account-api';
import { TRIAL_DAYS } from '../lib/plans';

/**
 * Тексти кабінету: екран до входу (двоє дверей — пошта й ключ ліцензії),
 * вхід за ключем і сам кабінет після входу.
 *
 * Слово «ключ» тут означає ключ ліцензії — рядок PHTM-…, що приходить листом
 * після покупки. Без нього в кабінеті не обійтись, тож де воно стоїть уперше,
 * там і сказано, що це таке.
 */
export interface OpsCopy {
  notTracked: string;
  sinceRestart: string;
  on: string;
  off: string;
  yes: string;
  no: string;
  claims: string;
  claimsEmpty: string;
  reports: string;
  reportsEmpty: string;
  uphold: string;
  dismiss: string;
  keepDecision: string;
  overturn: string;
  decisionReason: string;
  appeal: string;
  prevPage: string;
  nextPage: string;
  distance: string;
  evidence: string;
  mapNotes: string;
  mapNotesEmpty: string;
  venues: string;
  venuesEmpty: string;
  approve: string;
  reject: string;
  hide: string;
  restore: string;
  cancel: string;
  actionFailed: string;
  erasuresEmpty: string;
  grace: (d: number) => string;
  due: string;
  status: Record<'waiting' | 'overdue' | 'cancelled' | 'done', string>;
  kind: Record<'root' | 'account', string>;
  cluster: string;
  escrow: string;
  bills: string;
  proofsDevnet: string;
  proofsMainnet: string;
  hook: string;
  lastEvent: string;
  noEvent: string;
  accepted: string;
  hook503: string;
  watch503: string;
  watched: string;
  webhookDevnet: string;
  webhookMainnet: string;
  hookAuth: string;
  upstream: string;
  devicesToday: string;
  asksToday: string;
  byDay: string;
  asksEmpty: string;
  day: string;
  profile: string;
  model: string;
  asks: string;
  refused: string;
  failed: string;
  errorRate: string;
  fallback: string;
  health: string;
  healthy: string;
  relay: string;
  started: string;
  uptime: string;
  image: string;
  region: string;
  sha: string;
  siteBuild: string;
  roles: string;
  staffEmpty: string;
  ago: (s: number) => string;
}

export interface AccountCopy {
  meta: { title: string; description: string };
  hero: { eyebrow: string };
  loading: string;
  /** Людські назви ролей: «moderator» на бейджі — це назва поля в базі. */
  roles: Record<Role, string>;
  /** Екран без входу: двоє дверей в один кабінет. */
  doors: {
    eyebrow: string;
    title: string;
    lead: string;
    /** Питання, які людина ставить ДО того, як щось вводити. */
    answersTitle: string;
    answers: { q: string; a: string }[];
    or: string;
    keyDoorTitle: string;
    keyDoorLead: string;
    lostDoor: string;
    lostDoorLead: string;
    emailDoor: string;
    qr: {
      scan: string;
      newCode: string;
      retry: string;
      openApp: string;
      signedIn: string;
      expired: string;
      unreachable: string;
      useQr: string;
      showQr: string;
    };
  };
  login: {
    title: string;
    lead: string;
    email: string;
    emailPlaceholder: string;
    send: string;
    sending: string;
    codeTitle: string;
    codeLead: (email: string) => string;
    code: string;
    enter: string;
    entering: string;
    again: string;
    badEmail: string;
    wrong: string;
    offline: string;
    tooMany: string;
    note: string;
  };
  /** Вхід за ключем ліцензії, без пошти. */
  portal: {
    eyebrow: string;
    title: string;
    lead: string;
    keyLabel: string;
    keyHint: string;
    keyPlaceholder: string;
    submit: string;
    checking: string;
    noAccount: string;
    devicesTitle: string;
    activatedOn: string;
    emptyDevices: string;
    release: string;
    releasing: string;
    releaseHint: string;
    errors: {
      malformed: string;
      notFound: string;
      tooMany: string;
      offline: string;
      unknown: string;
    };
    recover: {
      open: string;
      title: string;
      lead: string;
      emailLabel: string;
      emailPlaceholder: string;
      send: string;
      sending: string;
      sent: string;
      codeLabel: string;
      codePlaceholder: string;
      confirm: string;
      confirming: string;
      done: string;
      warn: string;
      failed: string;
      offline: string;
      cancel: string;
    };
    cabinet: {
      signedIn: string;
      signOut: string;
      remember: string;
      copyKey: string;
      copied: string;
      statusActive: string;
      statusRevoked: string;
      seatsTitle: string;
      seatsFree: (n: number) => string;
      updatesTitle: string;
      updatesLeft: (days: number) => string;
      updatesEnded: string;
      updatesNone: string;
      perpetual: string;
      offlineTitle: string;
      offlineValue: (days: number) => string;
      offlineBody: () => string;
      rightsTitle: string;
      rightsLead: string;
      roadmapTag: string;
      lockedTag: string;
      certTitle: string;
      certBody: string;
      certMore: string;
      certKeyLabel: string;
      certUnknown: string;
      certFieldsLabel: string;
      certFields: [string, string][];
      windowFrom: string;
      windowTo: string;
      previewTitle: string;
      preview: string[];
    };
  };
  /** Підпис другої групи розділів. Персонал бачить десять пунктів, і без
   *  межі між «своїм» і «чужим» вони читаються як один довгий список. */
  navLabel: string;
  navStaff: string;
  nav: {
    licenses: string;
    downloads: string;
    places: string;
    threads: string;
    settings: string;
    security: string;
    staff: string;
    people: string;
    allLicenses: string;
    queue: string;
    journal: string;
    moderation: string;
    erasures: string;
    money: string;
    assistant: string;
    system: string;
  };
  ops: OpsCopy;
  licenses: {
    title: string;
    lead: string;
    bought: string;
    /** Підпис поля у формі видачі ліцензії персоналом. */
    tier: string;
    updates: string;
    active: string;
    revoked: string;
    revokedLead: string;
    writeUs: string;
    devices: string;
    release: string;
    releasing: string;
    none: string;
    updatesLeft: (days: number) => string;
    updatesEnded: string;
    updatesNone: string;
    /** Показуємо ЛИШЕ коли вікно вже закрилось — доти питання не стоїть. */
    windowEnded: string;
    /** Лише те, що ДОДАВ ключ. Повний перелік — робота сторінки цін. */
    rightsTitle: string;
    rightsFree: string;
    roadmapTag: string;
    rightsTrial: (days: number) => string;
    empty: string;
    emptyHint: (email: string) => string;
    claimTitle: string;
    claimLead: string;
    claimLabel: string;
    claim: string;
    claimed: string;
    claimFailed: string;
    /** Бета-ключ — самому, поки чинного ключа немає. Ключ їде лише листом. */
    betaTitle: string;
    betaLead: (email: string, tier: string) => string;
    betaGet: string;
    betaGetting: string;
    betaSent: (email: string) => string;
    betaUndelivered: string;
    betaHasKey: string;
    betaIssued: string;
    betaClosed: string;
    betaFailed: string;
    activated: string;
    /** Втрата ключа — головна аварія того, хто платив півроку тому. */
    lostTitle: string;
    lostLead: (email: string) => string;
    reissue: string;
    reissuing: string;
    reissueWarn: string;
    reissueConfirm: string;
    reissueCancel: string;
    reissueDone: (email: string) => string;
    reissueFailed: string;
    reissueTooMany: string;
    /** Адресу покупця тут НЕ називаємо: ключ бачить і той, кому його подарували. */
    notMine: string;
  };
  downloads: {
    title: string;
    lead: string;
    none: string;
    noneHint: string;
    get: string;
    preparing: string;
    failed: string;
    /** Показуємо лише під політикою «licence» — під «open» і «account» ключ не питають. */
    needLicense: string;
    needLicenseHint: string;
    needLicenseCta: string;
    /** Номер збірки поруч із версією: саме його звіряє телефон із собою. */
    buildTag: (build: number) => string;
    sha256: string;
    sha256Hint: string;
    copy: string;
    copied: string;
    platform: Record<string, string>;
    /** Що робити з файлом. Без цього APK на комп'ютері виглядає архівом. */
    install: Record<string, string>;
  };
  threads: {
    title: string;
    lead: string;
    newTitle: string;
    subject: string;
    body: string;
    send: string;
    sending: string;
    failed: string;
    empty: string;
    emptyHint: string;
    open: string;
    closed: string;
    you: string;
    team: string;
    reply: string;
    replyLabel: string;
    replying: string;
    replyHint: string;
    back: string;
    cancel: string;
  };
  settings: {
    title: string;
    lead: string;
    name: string;
    namePlaceholder: string;
    locale: string;
    save: string;
    saving: string;
    saved: string;
    failed: string;
    email: string;
    emailNote: string;
  };
  security: {
    title: string;
    lead: string;
    sessions: string;
    current: string;
    started: string;
    expires: string;
    revokeOne: string;
    revokingOne: string;
    revokeAll: string;
    revokeAllHint: string;
    revokeFailed: string;
    revoking: string;
    logout: string;
    deleteAccount: string;
  };
  staff: {
    title: string;
    lead: string;
    accounts: string;
    licenses: string;
    revoked: string;
    devices: string;
    waitlist: string;
    threadsOpen: string;
    unread: string;
    search: string;
    role: string;
    disabled: string;
    enable: string;
    disable: string;
    lastSeen: string;
    never: string;
    nothing: string;
    issueTitle: string;
    issueEmail: string;
    issue: string;
    issued: string;
    issuedNote: string;
    copy: string;
    copied: string;
    revoke: string;
    reason: string;
    assign: string;
    close: string;
    unanswered: string;
    journalLead: string;
    journalEmpty: string;
    denied: string;
    ownerOnly: string;
  };
}

const ukUpdatesLeft = (d: number) =>
  `${d} ${d % 10 === 1 && d % 100 !== 11 ? 'день' : d % 10 >= 2 && d % 10 <= 4 && (d % 100 < 10 || d % 100 >= 20) ? 'дні' : 'днів'}`;

const uk: AccountCopy = {
  meta: {
    title: 'Кабінет — Nightpost',
    description: 'Ліцензія, пристрої, завантаження й листування з командою Nightpost.',
  },
  hero: { eyebrow: 'Я' },
  loading: 'Відкриваємо кабінет…',
  roles: { user: 'Користувач', moderator: 'Модератор', admin: 'Адміністратор', owner: 'Власник' },
  doors: {
    eyebrow: 'Я',
    title: 'Ваші пристрої, завантаження й місця',
    lead: 'Вхід телефоном: відскануйте код у Nightpost або отримайте код поштою. Без пароля.',
    answersTitle: 'Перш ніж увійти',
    answers: [
      {
        q: 'Де кнопка «Реєстрація»?',
        a: 'Її немає. Відскануйте QR-код у застосунку або отримайте код із шести цифр на пошту — і кабінет створиться сам.',
      },
      {
        q: 'Застосунку потрібен акаунт?',
        a: 'Ні. Nightpost працює без акаунта й номера телефону. Кабінет потрібен лише для ваших пристроїв, завантажень і місць.',
      },
      {
        q: 'А пароль?',
        a: 'Пароля немає: щоразу це скан у застосунку або одноразовий код. Тож його не можна ні підібрати, ні вкрасти з нашої бази.',
      },
    ],
    or: 'або',
    keyDoorTitle: 'У мене є лише ключ ліцензії',
    keyDoorLead: 'Рівень, строк оновлень і пристрої видно й без пошти — досить ключа PHTM-…',
    lostDoor: 'Загубили ключ ліцензії?',
    lostDoorLead: 'Надішлемо новий на пошту, з якої купували. Старий одразу перестане діяти.',
    emailDoor: 'Увійти через пошту',
    qr: {
      scan: 'Скануйте в Nightpost',
      newCode: 'Новий код',
      retry: 'Спробувати знову',
      openApp: 'Відкрити в Nightpost',
      signedIn: 'Вхід виконано',
      expired: 'Строк дії минув',
      unreachable: 'Сервер не відповідає',
      useQr: 'Увійти за QR-кодом',
      showQr: 'Показати QR-код',
    },
  },
  login: {
    title: 'Вхід через пошту',
    lead: '',
    email: 'Пошта',
    emailPlaceholder: 'you@example.com',
    send: 'Надіслати код',
    sending: 'Надсилаємо…',
    codeTitle: 'Введіть код із листа',
    codeLead: (email) =>
      `Код надіслано на ${email}`,
    code: 'Код із листа',
    enter: 'Увійти',
    entering: 'Входимо…',
    again: 'Змінити пошту',
    badEmail: 'Перевірте адресу пошти.',
    wrong: 'Код не підійшов або застарів.',
    offline: 'Немає зʼєднання. Спробуйте ще раз.',
    tooMany: 'Забагато спроб. Спробуйте пізніше.',
    note: 'Код діє 15 хвилин і підходить лише для одного входу.',
  },
  portal: {
    eyebrow: 'Вхід за ключем',
    title: 'Ваша ліцензія і пристрої',
    lead: 'Ключ ліцензії — це рядок PHTM-…, який прийшов листом після покупки. Введіть його, щоб побачити рівень, строк оновлень і пристрої. Пошта для цього не потрібна.',
    keyLabel: 'Ключ ліцензії',
    keyHint: 'Можна вставити разом із пробілами — ми їх приберемо.',
    keyPlaceholder: 'PHTM-XXXXX-XXXXX-XXXXX-XXXX',
    submit: 'Перевірити ключ',
    checking: 'Перевіряємо…',
    noAccount: 'Тут ми не просимо ні пошти, ні пароля. Щоб завантажити застосунок або отримати новий ключ, увійдіть через пошту.',
    devicesTitle: 'Пристрої з цією ліцензією',
    activatedOn: 'активовано',
    emptyDevices: 'Ліцензію ще не активовано на жодному пристрої.',
    release: 'Звільнити місце',
    releasing: 'Звільняємо…',
    releaseHint:
      'Звільнене місце одразу може зайняти інший пристрій. Nightpost на тому пристрої не зникне — він перейде на безкоштовний рівень.',
    errors: {
      malformed: 'Ключ не схожий на наш. Перевірте, чи скопійовано його повністю — від PHTM до останньої групи.',
      notFound: 'Такого ключа ми не знаходимо. Перевірте, чи немає одруківки, або увійдіть через пошту, з якої купували.',
      tooMany: 'Забагато невдалих спроб поспіль. Зачекайте кілька хвилин і спробуйте знову.',
      offline: 'Сервер ліцензій не відповідає. Перевірте інтернет і спробуйте ще раз за хвилину.',
      unknown: 'Щось пішло не так на нашому боці. Спробуйте за хвилину, а якщо не мине — напишіть нам.',
    },
    recover: {
      open: 'Загубили ключ?',
      title: 'Новий ключ замість загубленого',
      lead: 'Сам ключ ми не зберігаємо — лише його відбиток, з якого ключ не відновити. Тому надішлемо новий, а старий одразу перестане діяти. Уже активовані пристрої лишаться активованими.',
      emailLabel: 'Пошта, з якої купували',
      emailPlaceholder: 'you@example.com',
      send: 'Надіслати код',
      sending: 'Надсилаємо…',
      sent: 'Якщо ця адреса є серед покупців, код уже в листі. Він діє 15 хвилин.',
      codeLabel: 'Код із листа',
      codePlaceholder: '000000',
      confirm: 'Отримати новий ключ',
      confirming: 'Перевіряємо…',
      done: 'Готово. Новий ключ надіслано вам на пошту — на сайті ми його не показуємо.',
      warn: 'Щойно ви підтвердите, старий ключ перестане діяти.',
      failed: 'Код не підійшов або вже минуло 15 хвилин. Перевірте цифри або почніть спочатку.',
      offline: 'Сервер не відповідає. Перевірте інтернет і спробуйте ще раз за хвилину.',
      cancel: 'Скасувати',
    },
    cabinet: {
      signedIn: 'Ключ',
      signOut: 'Вийти',
      remember: 'Запамʼятати ключ у цьому браузері',
      copyKey: 'Копіювати ключ',
      copied: 'Скопійовано',
      statusActive: 'Діє',
      statusRevoked: 'Відкликано',
      seatsTitle: 'Місця для пристроїв',
      seatsFree: (n) => (n === 0 ? 'Вільних місць немає' : n === 1 ? 'Вільне 1 місце' : `Вільних місць: ${n}`),
      updatesTitle: 'Оновлення',
      updatesLeft: (d) => `Ще ${ukUpdatesLeft(d)}`,
      updatesEnded: 'Строк оновлень минув',
      updatesNone: 'Строк оновлень не вказано',
      perpetual: 'Коли строк мине, версія, яку ви вже маєте, працюватиме далі — без обмежень у часі й без інтернету.',
      offlineTitle: 'Без інтернету',
      offlineValue: (d) => ukUpdatesLeft(d),
      offlineBody: () =>
        'Стільки ліцензія діє без жодного звʼязку з нами. Відлік іде від останньої вдалої перевірки, і кожна наступна починає його заново.',
      rightsTitle: 'Що відкриває ця ліцензія',
      rightsLead:
        `Перелік можливостей вашого рівня. На телефоні вони справді розділені: без ключа — ${TRIAL_DAYS} днів усього, далі безкоштовний рівень. На компʼютері обмеження сьогодні вимкнені, тож там увесь список відкритий у кожній копії. Те, що позначено «ще в роботі», ми не продаємо.`,
      roadmapTag: 'ще в роботі',
      lockedTag: 'вищий рівень',
      certTitle: 'Як перевірка працює без інтернету',
      certBody:
        'Під час активації пристрій отримує від нас підписане підтвердження: який у вас рівень і до якої дати оновлення. Пристрій перевіряє підпис сам, тож наш сервер для цього не потрібен.',
      certMore: 'Технічні подробиці',
      certKeyLabel: 'Публічний ключ, яким перевіряється підпис (Ed25519)',
      certUnknown: 'Зараз недоступний — сервер не відповів',
      certFieldsLabel: 'Що записано в підтвердженні',
      certFields: [
        ['tier', 'рівень ліцензії'],
        ['device_fingerprint', 'відбиток саме цього пристрою'],
        ['updates_until', 'дата, до якої діють оновлення'],
        ['serial', 'номер цієї активації'],
        ['issued_at', 'коли видано'],
        ['sig', 'підпис над усім переліченим'],
      ],
      windowFrom: 'з',
      windowTo: 'до',
      previewTitle: 'Що ви побачите',
      preview: [
        'Скільки пристроїв уже користуються ліцензією і скільки місць вільно',
        'До якої дати приходитимуть оновлення',
        'Що саме відкриває ваш рівень',
        'Як пристрій перевіряє ліцензію без інтернету',
      ],
    },
  },
  navLabel: 'Розділи кабінету',
  navStaff: 'Керування',
  nav: {
    licenses: 'Ліцензії',
    downloads: 'Завантаження',
    places: 'Місця',
    threads: 'Повідомлення',
    settings: 'Профіль',
    security: 'Безпека',
    staff: 'Зведення',
    people: 'Люди',
    allLicenses: 'Усі ліцензії',
    queue: 'Звернення',
    journal: 'Журнал',
    moderation: 'Модерація',
    erasures: 'Видалення',
    money: 'Гроші',
    assistant: 'Помічник',
    system: 'Система',
  },
  ops: {
    notTracked: 'Не відстежується',
    sinceRestart: 'з перезапуску',
    on: 'увімкнено',
    off: 'вимкнено',
    yes: 'так',
    no: 'ні',
    claims: 'Заявки на заклади',
    claimsEmpty: 'Заявок немає',
    reports: 'Скарги',
    reportsEmpty: 'Скарг немає',
    uphold: 'Підтвердити',
    dismiss: 'Без змін',
    keepDecision: 'Лишити рішення',
    overturn: 'Скасувати рішення',
    decisionReason: 'Причина',
    appeal: 'Оскарження',
    prevPage: 'Назад',
    nextPage: 'Далі',
    distance: 'відстань',
    evidence: 'Доказ',
    mapNotes: 'Нотатки з мапи',
    mapNotesEmpty: 'Нотаток немає',
    venues: 'Сторінки закладів',
    venuesEmpty: 'Закладів немає',
    approve: 'Схвалити',
    reject: 'Відхилити',
    hide: 'Сховати',
    restore: 'Повернути',
    cancel: 'Скасувати',
    actionFailed: 'Не вдалося. Спробуйте ще раз.',
    erasuresEmpty: 'Черга порожня',
    grace: (d) => `Строк очікування: ${d} д`,
    due: 'до',
    status: { waiting: 'чекає', overdue: 'прострочено', cancelled: 'скасовано', done: 'виконано' },
    kind: { root: 'ключ', account: 'кабінет' },
    cluster: 'Мережа',
    escrow: 'Ескроу',
    bills: 'Рахунки за фазами',
    proofsDevnet: 'Доведені тестові оплати',
    proofsMainnet: 'Доведені оплати mainnet',
    hook: 'Хук Helius',
    lastEvent: 'Остання подія',
    noEvent: 'ще не було',
    accepted: 'Прийнято',
    hook503: 'Хук 503',
    watch503: 'Стеження 503',
    watched: 'Під наглядом',
    webhookDevnet: 'Вебхук тестової мережі',
    webhookMainnet: 'Вебхук mainnet',
    hookAuth: 'Підпис хука',
    upstream: 'Джерело',
    devicesToday: 'Пристроїв сьогодні',
    asksToday: 'Запитів сьогодні',
    byDay: 'За днями',
    asksEmpty: 'Запитів ще не було',
    day: 'День',
    profile: 'Профіль',
    model: 'Модель',
    asks: 'Запити',
    refused: 'Відмови',
    failed: 'Збої',
    errorRate: 'Частка збоїв',
    fallback: 'Збої Vertex сьогодні',
    health: 'Стан',
    healthy: 'працює',
    relay: 'Сховок',
    started: 'Запущено',
    uptime: 'Без перезапуску',
    image: 'Образ',
    region: 'Регіон',
    sha: 'Коміт сервера',
    siteBuild: 'Збірка сайту',
    roles: 'Ролі',
    staffEmpty: 'Персоналу немає',
    ago: (s) => (s < 60 ? `${s} с тому` : s < 3600 ? `${Math.floor(s / 60)} хв тому` : s < 86400 ? `${Math.floor(s / 3600)} год тому` : `${Math.floor(s / 86400)} д тому`),
  },
  licenses: {
    title: 'Ліцензії та пристрої',
    lead: 'Тут видно, на яких пристроях діє ліцензія. Місце можна звільнити одразу, без листування з нами.',
    bought: 'Придбано',
    tier: 'Рівень',
    updates: 'Оновлення до',
    active: 'Діє',
    revoked: 'Відкликано',
    revokedLead:
      'Ця ліцензія більше не активує пристрої. Так буває після повернення коштів. Якщо це помилка — напишіть нам, розберемось.',
    writeUs: 'Написати нам',
    devices: 'Пристрої',
    release: 'Звільнити',
    releasing: 'Звільняємо…',
    none: 'Ліцензію ще не активовано на жодному пристрої.',
    updatesLeft: (d) => `ще ${ukUpdatesLeft(d)}`,
    updatesEnded: 'Строк оновлень минув',
    updatesNone: 'Строк оновлень не вказано',
    windowEnded: 'Куплена версія працює далі. Нових збірок після цієї дати ми не обіцяємо.',
    rightsTitle: 'Що додала ваша ліцензія',
    rightsFree:
      'Листування, мапа, навігація, тривоги, збережене й голос доступні в кожній копії — ліцензія для них не потрібна.',
    roadmapTag: 'ще в роботі',
    rightsTrial: (d) => `Перші ${d} днів після встановлення телефон відкриває все це й без ліцензії.`,
    empty: 'Ліцензій тут поки немає',
    emptyHint: (email) =>
      `Ви ввійшли як ${email}. Ліцензія, куплена на цю адресу, зʼявиться тут сама. Якщо платили з іншої пошти — увійдіть нею або додайте ключ нижче.`,
    claimTitle: 'Додати ліцензію за ключем',
    claimLead: 'Якщо ліцензію купили на іншу пошту або подарували, введіть її ключ — і вона зʼявиться в цьому кабінеті.',
    claimLabel: 'Ключ ліцензії',
    claim: 'Додати',
    claimed: 'Готово — ліцензію додано до кабінету.',
    claimFailed: 'Такого ключа немає або ліцензію відкликано. Перевірте, чи скопійовано ключ повністю.',
    betaTitle: 'Безкоштовна ліцензія на час бети',
    betaLead: (email, tier) =>
      `Поки триває бета, ліцензію рівня «${tier}» видаємо без оплати — одну на пошту. Ключ прийде листом на ${email}; на сайті ми його не показуємо.`,
    betaGet: 'Отримати ліцензію',
    betaGetting: 'Видаємо…',
    betaSent: (email) => `Ключ надіслано на ${email}. На сайті ми його не показуємо.`,
    betaUndelivered:
      'Ліцензію створено, але лист не пішов. Натисніть «Надіслати новий ключ» нижче — він прийде на ту саму адресу.',
    betaHasKey: 'У вас уже є ліцензія — вона в переліку вище.',
    betaIssued: 'Бета-ліцензію на цю пошту вже видавали. Якщо ключ загубився — напишіть нам.',
    betaClosed: 'Видача бета-ліцензій зараз зачинена. Спробуйте пізніше або напишіть нам.',
    betaFailed: 'Ліцензію не видано. Спробуйте ще раз за хвилину.',
    activated: 'активовано',
    lostTitle: 'Загубили ключ?',
    lostLead: (email) =>
      `Спершу пошукайте в скриньці ${email} — ключ приходив листом у день покупки. Не знайшли? Сам ключ ми не зберігаємо, лише його відбиток, тому надішлемо новий на ту саму адресу.`,
    reissue: 'Надіслати новий ключ',
    reissuing: 'Надсилаємо…',
    reissueWarn:
      'Старий ключ перестане діяти одразу. Уже активовані пристрої лишаться активованими — нічого переналаштовувати не треба.',
    reissueConfirm: 'Так, надіслати новий',
    reissueCancel: 'Скасувати',
    reissueDone: (email) => `Новий ключ надіслано на ${email}. На сайті ми його не показуємо.`,
    reissueFailed: 'Лист не пішов, тож ключ лишився попереднім. Спробуйте ще раз за хвилину.',
    reissueTooMany: 'Забагато запитів поспіль. Спробуйте знову за чверть години.',
    notMine:
      'Цю ліцензію купили з іншої пошти — новий ключ може отримати лише її власник. Якщо доступу до тієї пошти вже немає, напишіть нам.',
  },
  downloads: {
    title: 'Завантаження',
    lead: 'Посилання діє 12 годин, щоб великий файл можна було докачати на слабкому інтернеті. Воно не іменне: ці 12 годин файл зможе завантажити кожен, кому ви його перешлете.',
    none: 'Збірок ще не опубліковано',
    noneHint: 'Щойно вони зʼявляться, будуть тут — і в листі на вашу пошту.',
    get: 'Завантажити',
    preparing: 'Готуємо посилання…',
    failed: 'Посилання не вдалося отримати. Перевірте інтернет і спробуйте ще раз за хвилину.',
    needLicense: 'Ця збірка потребує ліцензії.',
    needLicenseHint: '',
    needLicenseCta: '',
    buildTag: (build) => `збірка ${build}`,
    sha256: 'Контрольна сума SHA-256',
    sha256Hint: 'Щоб переконатися, що файл не пошкоджено, порівняйте її із сумою завантаженого файла.',
    copy: 'Копіювати',
    copied: 'Скопійовано',
    platform: { android: 'Android', windows: 'Windows', linux: 'Linux', macos: 'macOS' },
    install: {
      android:
        'Завантажуйте з телефона. Відкрийте файл — Android спитає дозвіл встановлювати застосунки з браузера; дайте його й натисніть «Встановити». На компʼютері цей файл не запуститься: там він виглядає як архів.',
    },
  },
  threads: {
    title: 'Листування',
    lead: 'Напишіть нам тут — відповідь прийде в кабінет і не загубиться серед пошти.',
    newTitle: 'Нове звернення',
    subject: 'Тема',
    body: 'Що сталося',
    send: 'Надіслати',
    sending: 'Надсилаємо…',
    failed: 'Повідомлення не надіслано. Перевірте інтернет і спробуйте ще раз — текст ми зберегли.',
    empty: 'Звернень ще не було',
    emptyHint: 'Якщо щось не працює або є питання — напишіть, ми відповімо тут.',
    open: 'Відкрито',
    closed: 'Закрито',
    you: 'Ви',
    team: 'Команда Nightpost',
    reply: 'Відповісти',
    replyLabel: 'Ваша відповідь',
    replying: 'Надсилаємо…',
    replyHint: 'Напишіть відповідь…',
    back: 'До всіх звернень',
    cancel: 'Скасувати',
  },
  settings: {
    title: 'Профіль',
    lead: 'Імʼя бачить лише команда Nightpost, коли ви нам пишете.',
    name: 'Як до вас звертатися',
    namePlaceholder: 'Імʼя або позивний',
    locale: 'Мова листів',
    save: 'Зберегти',
    saving: 'Зберігаємо…',
    saved: 'Збережено',
    failed: 'Не вдалося зберегти. Перевірте інтернет і спробуйте ще раз.',
    email: 'Пошта',
    emailNote: 'Цією адресою ви входите. Змінити її може лише команда — напишіть нам у «Листуванні».',
  },
  security: {
    title: 'Безпека',
    lead: 'Кожен вхід діє 30 днів. Тут видно, де ви зараз увійшли.',
    sessions: 'Де ви увійшли',
    current: 'цей пристрій',
    started: 'Вхід',
    expires: 'Діє до',
    revokeOne: 'Відкликати',
    revokingOne: 'Відкликаємо…',
    revokeAll: 'Вийти на всіх пристроях',
    revokeFailed: 'Не вийшло. Сесії ще активні — спробуйте ще раз.',
    revokeAllHint: 'Корисно, якщо ви входили з чужого пристрою або загубили телефон. Вийде й з цього браузера.',
    revoking: 'Виходимо…',
    logout: 'Вийти',
    deleteAccount: 'Видалити акаунт',
  },
  staff: {
    title: 'Зведення',
    lead: 'Числа прямо з бази, без округлень.',
    accounts: 'Кабінетів',
    licenses: 'Чинних ліцензій',
    revoked: 'Відкликано',
    devices: 'Активних пристроїв',
    waitlist: 'У черзі на збірку',
    threadsOpen: 'Відкритих звернень',
    unread: 'Без відповіді',
    search: 'Пошук за поштою',
    role: 'Роль',
    disabled: 'вимкнено',
    enable: 'Увімкнути',
    disable: 'Вимкнути',
    lastSeen: 'востаннє',
    never: 'ще не заходив',
    nothing: 'Нічого не знайдено',
    issueTitle: 'Видати ліцензію',
    issueEmail: 'Пошта покупця',
    issue: 'Видати',
    issued: 'Ліцензію видано, ключ надіслано поштою',
    issuedNote: 'Ключ показано один раз — далі в базі лише його відбиток.',
    copy: 'Копіювати ключ',
    copied: 'Скопійовано',
    revoke: 'Відкликати',
    reason: 'Причина',
    assign: 'Взяти собі',
    close: 'Закрити звернення',
    unanswered: 'без відповіді',
    journalLead: 'Останні події сервера: активації, входи, зміни ролей.',
    journalEmpty: 'Подій ще немає',
    denied: 'Цей розділ недоступний для вашої ролі.',
    ownerOnly: 'Ролі призначає лише власник.',
  },
};

const en: AccountCopy = {
  meta: {
    title: 'Account — Nightpost',
    description: 'Your licence, devices, downloads and messages with the Nightpost team.',
  },
  hero: { eyebrow: 'You' },
  loading: 'Opening your account…',
  roles: { user: 'User', moderator: 'Moderator', admin: 'Administrator', owner: 'Owner' },
  doors: {
    eyebrow: 'You',
    title: 'Your devices, downloads and places',
    lead: 'Sign in with your phone: scan the code in Nightpost, or get a code by email. No password.',
    answersTitle: 'Before you sign in',
    answers: [
      {
        q: 'Where is the Register button?',
        a: 'There is none. Scan the QR code in the app or take a six-digit code by email, and the account creates itself.',
      },
      {
        q: 'Does the app need an account?',
        a: 'No. Nightpost works without an account or a phone number. This account is only for your devices, downloads and places.',
      },
      {
        q: 'And a password?',
        a: 'There is none: each sign-in is a scan in the app or a one-time code. So it cannot be guessed, and it cannot be stolen from our database.',
      },
    ],
    or: 'or',
    keyDoorTitle: 'I only have a licence key',
    keyDoorLead: 'Tier, update date and devices are visible without an email — the PHTM-… key is enough.',
    lostDoor: 'Lost your licence key?',
    lostDoorLead: 'We send a new one to the email you bought with. The old one stops working at once.',
    emailDoor: 'Sign in by email',
    qr: {
      scan: 'Scan with Nightpost',
      newCode: 'New code',
      retry: 'Retry',
      openApp: 'Open in Nightpost',
      signedIn: 'Signed in',
      expired: 'Expired',
      unreachable: 'The server is not answering',
      useQr: 'Scan with Nightpost',
      showQr: 'Show QR code',
    },
  },
  login: {
    title: 'Sign in by email',
    lead: '',
    email: 'Email',
    emailPlaceholder: 'you@example.com',
    send: 'Send code',
    sending: 'Sending…',
    codeTitle: 'Enter the code from the email',
    codeLead: (email) =>
      `Code sent to ${email}`,
    code: 'Code from the email',
    enter: 'Sign in',
    entering: 'Signing in…',
    again: 'Change email',
    badEmail: 'Check the email address.',
    wrong: 'Wrong or expired code.',
    offline: 'No connection. Try again.',
    tooMany: 'Too many attempts. Try later.',
    note: 'The code is valid for 15 minutes and works for a single sign-in.',
  },
  portal: {
    eyebrow: 'Sign in with a key',
    title: 'Your licence and devices',
    lead: 'A licence key is the PHTM-… line that arrived by email after purchase. Enter it to see your tier, update date and devices. No email needed.',
    keyLabel: 'Licence key',
    keyHint: 'You can paste it with spaces — we remove them.',
    keyPlaceholder: 'PHTM-XXXXX-XXXXX-XXXXX-XXXX',
    submit: 'Check key',
    checking: 'Checking…',
    noAccount: 'We ask for no email and no password here. To download the app or get a new key, sign in by email.',
    devicesTitle: 'Devices using this licence',
    activatedOn: 'activated',
    emptyDevices: 'The licence is not active on any device yet.',
    release: 'Free this seat',
    releasing: 'Freeing…',
    releaseHint:
      'A freed seat can be taken by another device right away. Nightpost does not disappear from that device — it moves to the free tier.',
    errors: {
      malformed: 'That does not look like our key. Check it was copied in full — from PHTM to the last group.',
      notFound: 'We cannot find that key. Check for a typo, or sign in with the email you bought with.',
      tooMany: 'Too many failed attempts in a row. Wait a few minutes and try again.',
      offline: 'The licence server is not answering. Check your connection and try again in a minute.',
      unknown: 'Something went wrong on our side. Try again in a minute, and if it persists, write to us.',
    },
    recover: {
      open: 'Lost your key?',
      title: 'A new key for a lost one',
      lead: 'We do not store the key itself — only a fingerprint it cannot be rebuilt from. So we send a new one, and the old one stops working at once. Devices already activated stay activated.',
      emailLabel: 'The email you bought with',
      emailPlaceholder: 'you@example.com',
      send: 'Send code',
      sending: 'Sending…',
      sent: 'If this address belongs to a buyer, the code is on its way. It is valid for 15 minutes.',
      codeLabel: 'Code from the email',
      codePlaceholder: '000000',
      confirm: 'Get a new key',
      confirming: 'Checking…',
      done: 'Done. The new key was emailed to you — we never show it on the site.',
      warn: 'The moment you confirm, the old key stops working.',
      failed: 'The code did not match, or 15 minutes have passed. Check the digits or start over.',
      offline: 'The server is not answering. Check your connection and try again in a minute.',
      cancel: 'Cancel',
    },
    cabinet: {
      signedIn: 'Key',
      signOut: 'Sign out',
      remember: 'Remember the key in this browser',
      copyKey: 'Copy key',
      copied: 'Copied',
      statusActive: 'Active',
      statusRevoked: 'Revoked',
      seatsTitle: 'Device seats',
      seatsFree: (n) => (n === 0 ? 'No seats free' : n === 1 ? '1 seat free' : `${n} seats free`),
      updatesTitle: 'Updates',
      updatesLeft: (d) => `${d} ${d === 1 ? 'day' : 'days'} left`,
      updatesEnded: 'Updates period ended',
      updatesNone: 'No update date set',
      perpetual: 'When the period ends, the version you already have keeps working — with no time limit and no internet.',
      offlineTitle: 'Without internet',
      offlineValue: (d) => `${d} days`,
      offlineBody: () =>
        'That is how long the licence works with no contact with us at all. It counts from the last successful check, and each new one starts it over.',
      rightsTitle: 'What this licence opens',
      rightsLead:
        `The features of your tier. On the phone they really are split: with no key you get ${TRIAL_DAYS} days of everything, then the free tier. On the computer the limits are switched off today, so every copy there has the whole list. Anything marked “still being built” is not sold.`,
      roadmapTag: 'still being built',
      lockedTag: 'higher tier',
      certTitle: 'How the check works without internet',
      certBody:
        'On activation the device receives a signed confirmation from us: your tier and the date updates run until. The device checks the signature itself, so our server is not needed for that.',
      certMore: 'Technical details',
      certKeyLabel: 'Public key that verifies the signature (Ed25519)',
      certUnknown: 'Unavailable right now — the server did not answer',
      certFieldsLabel: 'What the confirmation contains',
      certFields: [
        ['tier', 'the licence tier'],
        ['device_fingerprint', 'the fingerprint of this device'],
        ['updates_until', 'the date updates run until'],
        ['serial', 'the number of this activation'],
        ['issued_at', 'when it was issued'],
        ['sig', 'a signature over all of the above'],
      ],
      windowFrom: 'from',
      windowTo: 'until',
      previewTitle: 'What you will see',
      preview: [
        'How many devices already use the licence, and how many seats are free',
        'The date updates run until',
        'What exactly your tier opens',
        'How the device checks the licence without internet',
      ],
    },
  },
  navLabel: 'Account sections',
  navStaff: 'Management',
  nav: {
    licenses: 'Licences',
    downloads: 'Downloads',
    places: 'Places',
    threads: 'Messages',
    settings: 'Profile',
    security: 'Security',
    staff: 'Overview',
    people: 'People',
    allLicenses: 'All licences',
    queue: 'Requests',
    journal: 'Log',
    moderation: 'Moderation',
    erasures: 'Erasures',
    money: 'Money',
    assistant: 'Assistant',
    system: 'System',
  },
  ops: {
    notTracked: 'Not tracked',
    sinceRestart: 'since restart',
    on: 'on',
    off: 'off',
    yes: 'yes',
    no: 'no',
    claims: 'Venue claims',
    claimsEmpty: 'No claims',
    reports: 'Reports',
    reportsEmpty: 'No reports',
    uphold: 'Uphold',
    dismiss: 'Dismiss',
    keepDecision: 'Keep decision',
    overturn: 'Overturn',
    decisionReason: 'Reason',
    appeal: 'Appeal',
    prevPage: 'Previous',
    nextPage: 'Next',
    distance: 'distance',
    evidence: 'Evidence',
    mapNotes: 'Map notes',
    mapNotesEmpty: 'No notes',
    venues: 'Venue pages',
    venuesEmpty: 'No venues',
    approve: 'Approve',
    reject: 'Reject',
    hide: 'Hide',
    restore: 'Restore',
    cancel: 'Cancel',
    actionFailed: 'Failed. Try again.',
    erasuresEmpty: 'Queue is empty',
    grace: (d) => `Grace period: ${d} d`,
    due: 'due',
    status: { waiting: 'waiting', overdue: 'overdue', cancelled: 'cancelled', done: 'done' },
    kind: { root: 'key', account: 'account' },
    cluster: 'Network',
    escrow: 'Escrow',
    bills: 'Bills by phase',
    proofsDevnet: 'Proven test payments',
    proofsMainnet: 'Proven payments mainnet',
    hook: 'Helius hook',
    lastEvent: 'Last event',
    noEvent: 'none yet',
    accepted: 'Accepted',
    hook503: 'Hook 503',
    watch503: 'Watch 503',
    watched: 'Watched',
    webhookDevnet: 'Webhook, test network',
    webhookMainnet: 'Webhook mainnet',
    hookAuth: 'Hook signature',
    upstream: 'Upstream',
    devicesToday: 'Devices today',
    asksToday: 'Asks today',
    byDay: 'By day',
    asksEmpty: 'No asks yet',
    day: 'Day',
    profile: 'Profile',
    model: 'Model',
    asks: 'Asks',
    refused: 'Refused',
    failed: 'Failed',
    errorRate: 'Failure rate',
    fallback: 'Vertex failures today',
    health: 'Health',
    healthy: 'running',
    relay: 'Relay',
    started: 'Started',
    uptime: 'Uptime',
    image: 'Image',
    region: 'Region',
    sha: 'Server commit',
    siteBuild: 'Site build',
    roles: 'Roles',
    staffEmpty: 'No staff',
    ago: (s) => (s < 60 ? `${s}s ago` : s < 3600 ? `${Math.floor(s / 60)}m ago` : s < 86400 ? `${Math.floor(s / 3600)}h ago` : `${Math.floor(s / 86400)}d ago`),
  },
  licenses: {
    title: 'Licences and devices',
    lead: 'See which devices use your licence. You can free a seat right here, without writing to us.',
    bought: 'Bought',
    tier: 'Tier',
    updates: 'Updates until',
    active: 'Active',
    revoked: 'Revoked',
    revokedLead:
      'This licence no longer activates devices. That usually follows a refund. If it is a mistake, write to us and we will sort it out.',
    writeUs: 'Write to us',
    devices: 'Devices',
    release: 'Free seat',
    releasing: 'Freeing…',
    none: 'The licence is not active on any device yet.',
    updatesLeft: (d) => `${d} ${d === 1 ? 'day' : 'days'} left`,
    updatesEnded: 'Updates period ended',
    updatesNone: 'No update date set',
    windowEnded: 'The version you bought keeps working. We do not promise new builds after this date.',
    rightsTitle: 'What your licence added',
    rightsFree:
      'Messages, map, navigation, alerts, saved items and voice are in every copy — they need no licence.',
    roadmapTag: 'still being built',
    rightsTrial: (d) => `For the first ${d} days after install the phone opens all of this without a licence.`,
    empty: 'No licences here yet',
    emptyHint: (email) =>
      `You are signed in as ${email}. A licence bought with this address appears here by itself. If you paid from a different email, sign in with it — or add the key below.`,
    claimTitle: 'Add a licence by key',
    claimLead: 'If the licence was bought under another email or given to you, enter its key and it appears in this account.',
    claimLabel: 'Licence key',
    claim: 'Add',
    claimed: 'Done — the licence is now in your account.',
    claimFailed: 'No such key, or the licence was revoked. Check the key was copied in full.',
    betaTitle: 'A free licence during the beta',
    betaLead: (email, tier) =>
      `While the beta lasts, a “${tier}” licence is issued at no charge — one per email. The key arrives by email at ${email}; we never show it on the site.`,
    betaGet: 'Get the licence',
    betaGetting: 'Issuing…',
    betaSent: (email) => `The key was sent to ${email}. We never show it on the site.`,
    betaUndelivered:
      'The licence was created, but the email did not go out. Press “Send a new key” below — it goes to the same address.',
    betaHasKey: 'You already have a licence — it is in the list above.',
    betaIssued: 'A beta licence was already issued to this email. If the key is lost, write to us.',
    betaClosed: 'Beta licences are not being issued right now. Try later, or write to us.',
    betaFailed: 'The licence was not issued. Try again in a minute.',
    activated: 'activated',
    lostTitle: 'Lost your key?',
    lostLead: (email) =>
      `Search the ${email} mailbox first — the key arrived by email on the day of purchase. Not there? We do not store the key itself, only its fingerprint, so we send a new one to the same address.`,
    reissue: 'Send a new key',
    reissuing: 'Sending…',
    reissueWarn:
      'The old key stops working at once. Devices already activated stay activated — nothing needs setting up again.',
    reissueConfirm: 'Yes, send a new one',
    reissueCancel: 'Cancel',
    reissueDone: (email) => `The new key was sent to ${email}. We never show it on the site.`,
    reissueFailed: 'The email did not go out, so your key is unchanged. Try again in a minute.',
    reissueTooMany: 'Too many requests in a row. Try again in a quarter of an hour.',
    notMine:
      'This licence was bought from a different email — only its owner can get a new key. If you no longer have access to that mailbox, write to us.',
  },
  downloads: {
    title: 'Downloads',
    lead: 'The link works for 12 hours, so a large file can finish on a weak connection. It is not tied to you: for those 12 hours anyone you forward it to can download the file.',
    none: 'No builds published yet',
    noneHint: 'The moment they exist they appear here — and in an email to you.',
    get: 'Download',
    preparing: 'Preparing link…',
    failed: 'Could not get the link. Check your connection and try again in a minute.',
    needLicense: 'This build needs a licence.',
    needLicenseHint: '',
    needLicenseCta: '',
    buildTag: (build) => `build ${build}`,
    sha256: 'SHA-256 checksum',
    sha256Hint: 'To make sure the file is intact, compare it with the checksum of the downloaded file.',
    copy: 'Copy',
    copied: 'Copied',
    platform: { android: 'Android', windows: 'Windows', linux: 'Linux', macos: 'macOS' },
    install: {
      android:
        'Download it on the phone. Open the file — Android will ask permission to install apps from your browser; grant it and press Install. On a computer the file will not run: it looks like an archive there.',
    },
  },
  threads: {
    title: 'Messages',
    lead: 'Write to us here — the answer arrives in your account and does not get lost in your inbox.',
    newTitle: 'New request',
    subject: 'Subject',
    body: 'What happened',
    send: 'Send',
    sending: 'Sending…',
    failed: 'The message was not sent. Check your connection and try again — your text is kept.',
    empty: 'No requests yet',
    emptyHint: 'If something does not work or you have a question, write to us and we answer here.',
    open: 'Open',
    closed: 'Closed',
    you: 'You',
    team: 'Nightpost team',
    reply: 'Reply',
    replyLabel: 'Your reply',
    replying: 'Sending…',
    replyHint: 'Write a reply…',
    back: 'All requests',
    cancel: 'Cancel',
  },
  settings: {
    title: 'Profile',
    lead: 'Only the Nightpost team sees your name, when you write to us.',
    name: 'What to call you',
    namePlaceholder: 'Name or callsign',
    locale: 'Language of emails',
    save: 'Save',
    saving: 'Saving…',
    saved: 'Saved',
    failed: 'Could not save. Check your connection and try again.',
    email: 'Email',
    emailNote: 'You sign in with this address. Only the team can change it — write to us in Messages.',
  },
  security: {
    title: 'Security',
    lead: 'Each sign-in lasts 30 days. Here you can see where you are signed in.',
    sessions: 'Where you are signed in',
    current: 'this device',
    started: 'Signed in',
    expires: 'Valid until',
    revokeOne: 'Revoke',
    revokingOne: 'Revoking…',
    revokeAll: 'Sign out everywhere',
    revokeFailed: 'That did not go through. Sessions are still active — try again.',
    revokeAllHint: 'Useful if you signed in on someone else’s device or lost your phone. This browser is signed out too.',
    revoking: 'Signing out…',
    logout: 'Sign out',
    deleteAccount: 'Delete account',
  },
  staff: {
    title: 'Overview',
    lead: 'Numbers straight from the database, unrounded.',
    accounts: 'Accounts',
    licenses: 'Active licences',
    revoked: 'Revoked',
    devices: 'Active devices',
    waitlist: 'On the build waitlist',
    threadsOpen: 'Open requests',
    unread: 'Awaiting reply',
    search: 'Search by email',
    role: 'Role',
    disabled: 'disabled',
    enable: 'Enable',
    disable: 'Disable',
    lastSeen: 'last seen',
    never: 'never signed in',
    nothing: 'Nothing found',
    issueTitle: 'Issue a licence',
    issueEmail: 'Buyer email',
    issue: 'Issue',
    issued: 'Licence issued, key sent by email',
    issuedNote: 'The key is shown once — the database keeps only its fingerprint.',
    copy: 'Copy key',
    copied: 'Copied',
    revoke: 'Revoke',
    reason: 'Reason',
    assign: 'Take it',
    close: 'Close request',
    unanswered: 'no reply yet',
    journalLead: 'Recent server events: activations, sign-ins, role changes.',
    journalEmpty: 'No events yet',
    denied: 'This section is not available for your role.',
    ownerOnly: 'Only the owner assigns roles.',
  },
};

export const accountCopy: Record<Lang, AccountCopy> = { uk, en };
