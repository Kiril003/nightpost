import type { CtaBand, Item, Lang, PageMeta, Step } from './types';

/**
 * Сторінка завантажень.
 *
 * Редакційний закон: жодного числа, якого не можна звірити. Версія, номер
 * збірки, вага й SHA-256 приходять з `/api/downloads` (тобто з підписаної
 * політики) і стоять на екрані лише коли сервер їх назвав. Способи, яких ще
 * немає (магазини, програма для комп’ютера), названі так і є — без посилань,
 * без дат і без «готується до релізу» замість дати.
 */
export interface BuildCopy {
  title: string;
  ready: string;
  asking: string;
  /** Сервер відповів: збірки немає. */
  none: string;
  /** Сервер не відповів. Це НЕ «збірки немає» — цього ми не знаємо. */
  unreachable: string;
  /** Збірка є, але сервер віддає її лише після входу. Теж НЕ «немає». */
  gated: string;
  gatedCta: string;
  platform: Record<string, string>;
  /** `sha: 'SHA-256'` читає серверний тест (сторінка безпеки не сміє заперечувати суму). */
  labels: { build: string; size: string; sha: string; checksum: string; published: string };
  /**
   * Сервер не назвав дати. Рядок при цьому ЛИШАЄТЬСЯ на місці: прибрати його
   * означало б удати, що питання «а скільки їй років» ніхто не ставив.
   */
  noPublished: string;
  /** Вік у повних добах. Стоїть біля дати, а не замість неї. */
  age: (days: number) => string;
  mb: string;
  copy: string;
  copied: string;
  /**
   * Те, що сервер роздає сьогодні, — не реліз.
   *
   * Ознаку беремо з відповіді САМОГО сервера (`-debug` у версії або в імені
   * файла), а не з рядка тут: інакше цей блок довелось би знімати руками в
   * день випуску. Зникне з імені — зникне з екрана.
   */
  debug: { label: string; text: string };
  noSha: string;
  toDownload: string;
  action: string;
  /** Та сама дія для збірки не для Android (компʼютер), якщо сервер її назве. */
  actionFor: (platform: string) => string;
  preparing: string;
  failed: string;
  /** Вага — лише з виміряного `size`; жодного числа з голови. */
  weight: (mb: string) => string;
  /**
   * Що докачається ПІСЛЯ встановлення.
   *
   * Кожне число тут — з КОНСТАНТИ застосунку, а не з опису:
   * `ModelManager.downloadBytes = 137L * 1024 * 1024` (vosk з alphacephei.com,
   * без очікування на Wi-Fi — `ensureHearing(urgent = true)`). Преміального
   * голосу в пакунку релізу немає (`RoutedTts` падає на системний голос), тож
   * «голос не працює» було б суворіше за правду — застосунок озвучує.
   * «416 МБ на диску» не повертати, доки його не заміряно.
   *
   * Сторінка не описує ПОВЕДІНКУ ЕКРАНА, поки цей екран не побачили.
   */
  models: string;
  linkTerms: string;
  /**
   * Що людина зможе робити БЕЗ мережі. Обидва стани приходять від СЕРВЕРА:
   * доки він не назвав пакетів, картка каже, що роздачі немає.
   */
  offline: {
    label: string;
    catalogued: string;
    ready: (countries: string, mb: string) => string;
    door: string;
  };
}

/** Спосіб, якого ще немає. Ні посилання, ні дати — лише що це й що зараз. */
export interface SoonWay {
  id: string;
  name: string;
  desc: string;
  note: string;
}

/** Як сервер відповів про файл — коротко, для змісту вгорі сторінки. */
export interface ServerStates {
  asking: string;
  ready: string;
  /** Файл є, але це тестова збірка (`-debug`): «Доступно» зеленим тут збрехало б гучністю. */
  test: string;
  none: string;
  gated: string;
  unreachable: string;
}

export interface DownloadCopy {
  meta: PageMeta;
  build: BuildCopy;
  hero: { eyebrow: string; title: string; sub: string };
  ways: {
    eyebrow: string;
    title: string;
    lead: string;
    /** Підпис змісту вгорі (для читачів екрана). */
    index: string;
    soon: string;
    stores: SoonWay[];
    apk: {
      id: string;
      name: string;
      desc: string;
      state: ServerStates;
      stepsTitle: string;
      steps: string[];
    };
    desktop: {
      id: string;
      name: string;
      /** Стан, коли сервер не називає збірки для комп’ютера. */
      state: string;
      desc: string;
      others: string;
    };
  };
  verify: {
    eyebrow: string;
    title: string;
    lead: string;
    steps: Step[];
    shellLabel: string;
    command: (filename: string) => string;
    windowsLabel: string;
    windowsCommand: (filename: string) => string;
    shellNote: string;
    /** Імʼя файлу в команді, доки сервер не назвав справжнього. */
    fileHint: string;
  };
  updates: { eyebrow: string; title: string; lead: string; points: Item[] };
  cta: CtaBand;
}

const uk: DownloadCopy = {
  meta: {
    title: 'Завантажити Nightpost — файл для Android і що буде далі',
    description:
      'Файл для Android — прямо з нашого сервера після входу поштою, з версією, вагою й контрольною сумою. Google Play і Solana dApp Store — скоро. Програми для компʼютера на роздачі ще немає.',
  },
  build: {
    title: 'Файл на сервері зараз',
    ready: 'Можна завантажити',
    asking: 'Питаємо сервер…',
    none: 'Файлу ще немає. Щойно він зʼявиться на сервері, ця картка покаже його сама.',
    unreachable:
      'Сервер не відповів. Це не означає, що файлу немає, — оновіть сторінку трохи згодом або напишіть нам.',
    gated:
      'Файл є, але зараз сервер віддає його лише після входу в кабінет. Увійдіть поштою — версія, вага й контрольна сума будуть там.',
    gatedCta: 'До кабінету',
    platform: { android: 'Android', linux: 'Linux', windows: 'Windows' },
    labels: { build: 'Збірка', size: 'Вага', sha: 'SHA-256', checksum: 'Контрольна сума', published: 'Викладено' },
    noPublished: 'сервер не назвав дати',
    // «Викладено», а не «зібрано»: дата приходить з об'єкта у сховищі, тобто
    // каже, відколи ці байти лежать, а не коли працював компілятор.
    age: (days) => {
      if (days === 0) return 'сьогодні';
      if (days === 1) return 'учора';
      const tail = days % 100 >= 11 && days % 100 <= 14 ? 'днів' : [null, 'день', 'дні', 'дні', 'дні'][days % 10] ?? 'днів';
      return `${days} ${tail} тому`;
    },
    mb: 'МБ',
    copy: 'Копіювати',
    copied: 'Скопійовано',
    debug: {
      label: 'Це тестова збірка, а не реліз',
      text:
        'Сервер зараз роздає файл із позначкою debug. Він важить у кілька разів більше за звичайний і не оптимізований. Беріть його, щоб подивитися й розповісти нам, що не так, — але не як щоденний застосунок. Коли на сервері зʼявиться реліз, це попередження зникне само.',
    },
    noSha: 'Контрольної суми для цього файлу сервер не назвав — звіряти нема з чим.',
    toDownload: 'До завантаження',
    action: 'Завантажити для Android',
    actionFor: (platform) => `Завантажити для ${platform}`,
    preparing: 'Готуємо посилання…',
    failed: 'Посилання не вдалося отримати. Спробуйте ще раз або напишіть нам.',
    weight: (mb) => `Файл APK, ≈${mb} МБ · Android 10 і новіші · без акаунта.`,
    models:
      'Це вага самого файлу. Коли ви вперше торкнетеся мікрофона, застосунок докачає розпізнавання мовлення — 137 МБ з alphacephei.com, навіть через мобільний інтернет. Преміального голосу в цій збірці немає, тож застосунок говорить системним голосом Android.',
    linkTerms: 'Посилання діє 12 годин і не привʼязане до вас: файл отримає кожен, у кого є посилання.',
    offline: {
      label: 'Маршрути без інтернету',
      catalogued:
        'Маршрут без інтернету будується з дорожнього пакета країни. Пакети вже виміряні, але роздавати їх ще не почали.',
      ready: (countries, mb) =>
        `Маршрут без інтернету будується з дорожнього пакета країни. ${countries}, від ${mb} МБ, контрольна сума поруч із кожним.`,
      door: 'Що відомо про пакети',
    },
  },
  hero: {
    eyebrow: 'Завантажити',
    title: 'Як поставити Nightpost',
    sub: 'Сьогодні Nightpost для Android можна взяти одним способом — файлом прямо з нашого сервера після входу поштою. Магазини й програма для компʼютера ще в дорозі, і нижче чесно написано, що з чим.',
  },
  ways: {
    eyebrow: 'Способи встановлення',
    title: 'Чотири способи, і зараз працює один',
    lead: 'Ось усі способи в тому порядку, у якому їх шукають. Де написано «Скоро», там поки нічого немає: ні сторінки, ні посилання, ні дати.',
    index: 'Способи встановлення',
    soon: 'Скоро',
    stores: [
      {
        id: 'google-play',
        name: 'Google Play',
        desc: 'Звичайне встановлення з магазину, з оновленнями через Google Play.',
        note: 'Сторінки Nightpost у Google Play ще немає. Коли вона зʼявиться, посилання стоятиме тут.',
      },
      {
        id: 'solana',
        name: 'Solana dApp Store',
        desc: 'Магазин застосунків на телефонах Solana Mobile.',
        note: 'Сторінки Nightpost у Solana dApp Store ще немає. Коли вона зʼявиться, посилання стоятиме тут.',
      },
    ],
    apk: {
      id: 'apk',
      name: 'Файл APK напряму',
      desc: 'Файл для Android, який ви завантажуєте з нашого сервера після входу й ставите самі — без магазину. Версія, вага й контрольна сума в картці приходять від сервера, а не з тексту сторінки.',
      state: {
        asking: 'Питаємо сервер',
        ready: 'Доступно',
        test: 'Доступна тестова збірка',
        none: 'Файлу ще немає',
        gated: 'Після входу',
        unreachable: 'Сервер не відповів',
      },
      stepsTitle: 'Як встановити не з магазину',
      steps: [
        'Завантажте файл на сам телефон — у браузері на ньому, а не на компʼютері.',
        'Відкрийте файл. Android попередить, що застосунок не з магазину, і попросить дозволу встановлювати з браузера. Це звичайне питання для будь-якого файлу APK.',
        'Дайте дозвіл і натисніть «Встановити». Після цього дозвіл можна забрати в налаштуваннях.',
        'Нову версію ставте поверх старої — ваші розмови лишаться на місці.',
      ],
    },
    desktop: {
      id: 'desktop',
      name: 'Компʼютер: Windows і Linux',
      state: 'Ще немає',
      desc: 'Програма для компʼютера з Windows або Linux у роботі, але на роздачі її ще немає. Дат не називаємо. Залиште пошту — напишемо один раз, коли можна буде завантажити.',
      others: 'Для macOS та iPhone збірок немає, і зараз над ними не працюємо.',
    },
  },
  verify: {
    eyebrow: 'Перевірка',
    title: 'Як переконатися, що файл саме наш',
    lead: 'Контрольна сума — це відбиток файлу. Якщо змінився хоч один байт, відбиток буде інший. Порівняйте два рядки — і знатимете напевно.',
    steps: [
      {
        k: '01',
        title: 'Скопіюйте суму з картки',
        desc: 'Сума в картці приходить від сервера разом із файлом, а не з тексту цієї сторінки.',
      },
      {
        k: '02',
        title: 'Порахуйте суму файлу',
        desc: 'На компʼютері для цього досить однієї команди — вона нижче.',
      },
      {
        k: '03',
        title: 'Порівняйте',
        desc: 'Збіглося — файл наш. Не збіглося — видаліть його й напишіть нам.',
      },
      {
        k: '04',
        title: 'Далі застосунок пильнує сам',
        desc: 'Перш ніж запропонувати оновлення, застосунок перевіряє підпис нашого сервера. А Android не поставить поверх Nightpost версію, підписану кимось іншим.',
      },
    ],
    shellLabel: 'Linux або macOS',
    command: (filename) => `sha256sum ${filename}`,
    windowsLabel: 'Windows',
    windowsCommand: (filename) => `certutil -hashfile ${filename} SHA256`,
    shellNote: 'Порівняйте результат із сумою в картці.',
    fileHint: '<файл>',
  },
  updates: {
    eyebrow: 'Оновлення',
    title: 'Як приходить нова версія',
    lead: 'Нічого не завантажується й не встановлюється без вас. Застосунок лише повідомляє, що є новіша версія; файл завантажується після вашого дотику, а «Встановити» натискаєте ви.',
    points: [
      {
        title: 'Перевіряє під час запуску',
        desc: 'Під час запуску застосунок питає наш сервер, чи є новіша версія, і показує її номер. Без інтернету він просто працює далі.',
      },
      {
        title: 'Поверх, а не заново',
        desc: 'Нова версія ставиться поверх старої. Розмови й налаштування лишаються — стерти їх може лише видалення застосунку.',
      },
      {
        title: 'Безкоштовне не звужується',
        desc: 'Листування, мапа, навігація, тривоги, сховище й голос вбудовані в сам застосунок. Сервер відмовиться видати налаштування, які б їх урізали.',
      },
      {
        title: 'Посилання живе 12 годин',
        desc: 'Щоб великий файл можна було докачати на поганому звʼязку. Посилання не іменне: файл отримає кожен, кому ви його перешлете.',
      },
    ],
  },
  cta: {
    title: 'Увійдіть поштою — і завантажуйте',
    sub: 'Той самий кабінет показує вашу ліцензію й пристрої і тримає листування з нами в одному місці, а не в пошті.',
    primary: 'Завантажити для Android',
    secondary: 'Написати нам',
  },
};

const en: DownloadCopy = {
  meta: {
    title: 'Download Nightpost — the Android file, and what comes next',
    description:
      'The Android file comes straight from our server after you sign in by email, with its version, size and checksum. Google Play and the Solana dApp Store are coming. There is no desktop build to download yet.',
  },
  build: {
    title: 'The file on the server now',
    ready: 'Available to download',
    asking: 'Asking the server…',
    none: 'There is no file yet. As soon as the server has one, this card shows it by itself.',
    unreachable:
      'The server did not answer. That does not mean there is no file — reload the page in a little while, or write to us.',
    gated:
      'The file exists, but right now the server hands it out only after you sign in. Sign in by email — the version, size and checksum are there.',
    gatedCta: 'Open account',
    platform: { android: 'Android', linux: 'Linux', windows: 'Windows' },
    labels: { build: 'Build', size: 'Size', sha: 'SHA-256', checksum: 'Checksum', published: 'Uploaded' },
    noPublished: 'the server named no date',
    age: (days) => (days === 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`),
    mb: 'MB',
    copy: 'Copy',
    copied: 'Copied',
    debug: {
      label: 'This is a test build, not a release',
      text:
        'The server is handing out a file marked debug. It weighs several times what a normal one does and is not optimised. Take it to look around and tell us what is wrong — not as your everyday app. When a release is on the server, this warning disappears by itself.',
    },
    noSha: 'The server named no checksum for this file — there is nothing to check against.',
    toDownload: 'To the download',
    action: 'Download for Android',
    actionFor: (platform) => `Download for ${platform}`,
    preparing: 'Preparing the link…',
    failed: 'We could not get the link. Try again, or write to us.',
    weight: (mb) => `APK file, ≈${mb} MB · Android 10 or newer · no account.`,
    models:
      'That is the size of the file alone. The first time you touch the microphone, the app fetches speech recognition — 137 MB from alphacephei.com, even over mobile data. There is no premium voice in this build, so the app speaks with the Android system voice.',
    linkTerms: 'The link works for 12 hours and is not tied to you: anyone who has the link gets the file.',
    offline: {
      label: 'Routes without internet',
      catalogued:
        'A route without internet is built from a country’s road pack. The packs are measured, but handing them out has not started.',
      ready: (countries, mb) =>
        `A route without internet is built from a country’s road pack. ${countries}, from ${mb} MB, a checksum beside each.`,
      door: 'What we know about the packs',
    },
  },
  hero: {
    eyebrow: 'Download',
    title: 'How to get Nightpost',
    sub: 'Today there is one way to get Nightpost for Android — a file straight from our server, after you sign in by email. The stores and the desktop app are on their way, and below says honestly where each one stands.',
  },
  ways: {
    eyebrow: 'Ways to install',
    title: 'Four ways, and one works today',
    lead: 'Here are all of them, in the order people look for them. Where it says “Coming soon”, there is nothing yet: no page, no link, no date.',
    index: 'Ways to install',
    soon: 'Coming soon',
    stores: [
      {
        id: 'google-play',
        name: 'Google Play',
        desc: 'A normal install from the store, with updates through Google Play.',
        note: 'Nightpost has no Google Play page yet. When it does, the link will be here.',
      },
      {
        id: 'solana',
        name: 'Solana dApp Store',
        desc: 'The app store on Solana Mobile phones.',
        note: 'Nightpost has no Solana dApp Store page yet. When it does, the link will be here.',
      },
    ],
    apk: {
      id: 'apk',
      name: 'APK file, directly',
      desc: 'An Android file you download from our server after signing in, and install yourself — no store. The version, size and checksum in the card come from the server, not from this page’s text.',
      state: {
        asking: 'Asking the server',
        ready: 'Available',
        test: 'Test build available',
        none: 'No file yet',
        gated: 'After sign-in',
        unreachable: 'Server did not answer',
      },
      stepsTitle: 'Installing outside a store',
      steps: [
        'Download the file on the phone itself — in its browser, not on a computer.',
        'Open the file. Android warns that the app is not from a store and asks permission to install from the browser. It asks this for any APK file.',
        'Grant it and tap Install. You can take the permission back in settings afterwards.',
        'Install new versions over the old one — your conversations stay where they are.',
      ],
    },
    desktop: {
      id: 'desktop',
      name: 'Computer: Windows and Linux',
      state: 'Not yet',
      desc: 'The app for Windows and Linux computers is in the works, but there is nothing to download yet. We do not give dates. Leave your email and we will write once, when you can download it.',
      others: 'There are no builds for macOS or iPhone, and we are not working on them now.',
    },
  },
  verify: {
    eyebrow: 'Checking',
    title: 'How to be sure the file is ours',
    lead: 'A checksum is the file’s fingerprint. Change a single byte and the fingerprint changes. Compare two lines and you know for certain.',
    steps: [
      {
        k: '01',
        title: 'Copy the checksum from the card',
        desc: 'The checksum in the card comes from the server along with the file, not from this page’s text.',
      },
      {
        k: '02',
        title: 'Compute the file’s checksum',
        desc: 'On a computer it takes one command — it is below.',
      },
      {
        k: '03',
        title: 'Compare',
        desc: 'They match — the file is ours. They do not — delete it and write to us.',
      },
      {
        k: '04',
        title: 'From then on, the app checks',
        desc: 'Before offering an update, the app verifies our server’s signature. And Android will not install a version over Nightpost that someone else signed.',
      },
    ],
    shellLabel: 'Linux or macOS',
    command: (filename) => `sha256sum ${filename}`,
    windowsLabel: 'Windows',
    windowsCommand: (filename) => `certutil -hashfile ${filename} SHA256`,
    shellNote: 'Compare the result with the checksum in the card.',
    fileHint: '<file>',
  },
  updates: {
    eyebrow: 'Updates',
    title: 'How a new version arrives',
    lead: 'Nothing downloads or installs without you. The app only tells you a newer version exists; the file downloads after your tap, and you are the one who taps Install.',
    points: [
      {
        title: 'Checks at start',
        desc: 'At start the app asks our server whether there is a newer version and shows its number. Without internet it simply carries on.',
      },
      {
        title: 'Over, not from scratch',
        desc: 'A new version installs over the old one. Conversations and settings stay — only uninstalling the app removes them.',
      },
      {
        title: 'The free part never shrinks',
        desc: 'Messaging, the map, navigation, alerts, the vault and voice are built into the app itself. The server refuses to issue settings that would cut them.',
      },
      {
        title: 'The link lives 12 hours',
        desc: 'So a large file can finish on a poor connection. The link is not personal: anyone you forward it to gets the file.',
      },
    ],
  },
  cta: {
    title: 'Sign in by email, then download',
    sub: 'The same account shows your licence and devices, and keeps your messages with us in one place instead of in email.',
    primary: 'Download for Android',
    secondary: 'Write to us',
  },
};

export const downloadCopy: Record<Lang, DownloadCopy> = { en, uk };
