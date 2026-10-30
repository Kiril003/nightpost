import type { PageMeta } from './types';

export const APK_URL = 'https://github.com/Kiril003/phantom-solana/raw/main/nightpost.apk';
export const APK_SHA256 = '97d2506d68d425d40b0d0b02ab8b9beb5c72e2f247ae805edfd8545d4b1bde49';
export const GITHUB_URL = 'https://github.com/Kiril003/phantom-solana';
export const TX_SIG =
  'RkJ9GoQ5jSyvF86BjHWmaVZ38NRkk6Tb9brBHsgP72F69YaG6crDJR27VEKL7Xe3s5zZ257VQbWY2mfsfQQP8wC';
export const TX_URL = `https://explorer.solana.com/tx/${TX_SIG}?cluster=devnet`;
export const TX_SLOT = 509953198;
export const DEVNET_RPC = 'https://api.devnet.solana.com';
export const CONTACT = 'support@phantom-os.dev';

/** Scroll-scrubbed recordings (GOP 4). Marks and cuts are the seconds where the app changes state. */
export const PEOPLE = { src: '/v9/people.mp4', poster: '/v9/people.webp', dur: 8, marks: [0, 1.2, 3, 5] } as const;
export const FLOW = { src: '/v9/flow.mp4', poster: '/v9/flow.webp', dur: 16.33, cuts: [0.2, 4.6, 11.4, 16.33] } as const;
export const ASK_TYPED = 'Send 5 USDC to Daniel';
export const ASK_CLIP = '/v7/clips/b11.mp4';
export const LOOK = { day: '/v9/look-day.webp', night: '/v9/look-night.webp' } as const;
export const DEMO = { src: '/v9/demo.mp4', poster: '/v9/demo.webp' } as const;
export const TURN = { dir: '/v9/turn', frames: 36, ms: 1400 } as const;
export const FLAT = '/v9/flat.webp';

/** Where each part starts in public/v7/clips/hero.mp4 (seconds); the last value is the length. */
export const HERO_CUTS = [0, 2.4, 5.0, 7.8, 10.6, 13.2, 15.8, 18.8, 21.6, 24.4, 27.6] as const;

export interface HomeV7Copy {
  meta: PageMeta;
  nav: { how: string; solana: string; demo: string; get: string; signIn: string; menu: string; close: string };
  hero: {
    a: string;
    b: string;
    sub: string;
    get: string;
    getNote: string;
    watch: string;
    watchNote: string;
    status: string[];
    chapters: string[];
    apps: string[];
    pause: string;
    ios: string;
    play: string;
  };
  people: { eyebrow: string; head: string; line: string; chips: string[] };
  flow: { eyebrow: string; head: string; stages: { name: string; head: string; line: string }[] };
  ask: { eyebrow: string; head: string; prompt: string; line: string };
  look: { eyebrow: string; head: string; line: string; label: string; day: string; night: string };
  also: { head: string; items: string[] };
  sol: {
    eyebrow: string;
    head: string;
    line: string;
    facts: { head: string; line: string }[];
    receipt: {
      title: string;
      net: string;
      rows: [string, string][];
      memo: string;
      open: string;
      foot: string;
      confirm: string;
      live: { head: string; state: string; checking: string; finalized: string; unknown: string; unconfirmed: string; now: string; later: string };
    };
    status: string;
    next: string;
  };
  proof: {
    head: string;
    line: string;
    play: string;
    stats: { n: string; t: string }[];
    links: { github: string; chain: string };
    close: string;
  };
  get: {
    head: string;
    line: string;
    button: string;
    scan: string;
    sha: string;
    copy: string;
    copied: string;
    steps: string[];
    more: string;
    next: string;
  };
  foot: { made: string; screens: string; download: string; pricing: string; account: string; security: string; legal: string; github: string; contact: string; lang: string };
}

export const homeV7: Record<'en' | 'uk', HomeV7Copy> = {
  en: {
    meta: {
      title: 'Nightpost: everything your phone is for, joined',
      description:
        'Chats, people, moments, plans, places and money in one Android app, with an assistant across it. Payments settle on Solana.',
    },
    nav: { how: 'How it works', solana: 'Solana', demo: 'Demo', get: 'Download', signIn: 'Sign in', menu: 'Menu', close: 'Close' },
    hero: {
      a: 'Everything your phone is for,',
      b: 'joined.',
      sub: 'Chats, people, moments, plans, places and money in one app, with an assistant across it. Payments settle on Solana.',
      get: 'Download for Android',
      getNote: 'APK',
      watch: 'Watch the demo',
      watchNote: '2:19',
      status: ['Android 10+', 'Test payments on Solana'],
      chapters: ['Today', 'People', 'Talk', 'Moments', 'Plans', 'Places', 'Split', 'Assistant', 'Wallet', 'Your look'],
      apps: ['Messenger', 'Maps', 'Calendar', 'Photos', 'Bank', 'Bill splitter'],
      pause: 'Pause',
      ios: 'Android only for now. On iPhone, watch the demo.',
      play: 'Play',
    },
    people: {
      eyebrow: 'People',
      head: 'Your people, by how close.',
      line: 'Chats without a phone number, groups up to 32, voice notes and calls. The ones who matter come first, and a moment goes only to the people you pick.',
      chips: ['Close', 'Friends', 'Work', 'All'],
    },
    flow: {
      eyebrow: 'Plans, places, money',
      head: 'From “let’s eat” to settled.',
      stages: [
        { name: 'Plan', head: 'Who’s in, right in the chat.', line: 'A meetup card with the place, the time and who’s going. One tap: I’m in.' },
        { name: 'Place', head: 'The place has its own page.', line: 'Hours and menu, set by whoever runs it. Meet there in one tap.' },
        { name: 'Split', head: 'Split by what you had.', line: 'Everyone ticks their own dishes and pays their share in USDC.' },
      ],
    },
    ask: {
      eyebrow: 'Assistant',
      head: 'Ask once. You confirm.',
      prompt: 'Send 5 USDC to Daniel',
      line: 'The assistant drafts the payment. Nothing leaves until you confirm with your fingerprint or PIN.',
    },
    look: {
      eyebrow: 'Your look',
      head: 'Make it yours.',
      line: 'Day, night or follow the sun. Your accent, and a wallpaper for each chat.',
      label: 'Theme',
      day: 'Day',
      night: 'Night',
    },
    also: {
      head: 'Also in the app',
      items: [
        'A map of the friends who chose to share, until a time they set',
        'Messages are end-to-end encrypted',
        'One-tap calls from any chat',
        'Saved messages, always at the top',
      ],
    },
    sol: {
      eyebrow: 'On Solana',
      head: 'Money moves like a message.',
      line: 'Payments between friends are small, frequent and social. They need a transfer that settles in seconds, costs a fraction of a cent and is signed on the phone.',
      facts: [
        { head: 'Pay a person, not an address.', line: 'USDC, EURC and SOL, sent from the chat to someone you know.' },
        {
          head: 'The chain shows who paid.',
          line: 'Each share of a bill is its own TransferChecked, with the bill id in an SPL Memo.',
        },
        {
          head: 'Keys stay on the phone.',
          line: 'Android Keystore. On Seeker, Mobile Wallet Adapter and Seed Vault.',
        },
        { head: 'Seeker, recognised.', line: 'The Genesis Token and SKR stake tier show as a profile badge, read-only.' },
      ],
      receipt: {
        title: 'A bill share, sent from the chat',
        net: 'Solana test',
        rows: [
          ['Amount', '25 USDC'],
          ['From', 'vqNi…7JD2'],
          ['To', '2ZWP…oA8g'],
          ['Instructions', 'Create account · TransferChecked · Memo'],
          ['Network fee', '0.000005 SOL'],
          ['Slot', '509,953,198'],
          ['Time', '11 Oct 2026, 15:51 UTC'],
        ],
        memo: 'nightpost-bill:v1:1421ac56-95ab-4d2a-94af-a264bb1d73c6',
        open: 'Open in Solana Explorer',
        foot: 'A real test-network transaction, made from the app with test funds.',
        confirm: 'Confirmed on the phone',
        live: {
          head: 'Live from the test network',
          state: 'Status',
          checking: 'Asking the test network…',
          finalized: 'Finalized',
          unknown: 'The test network did not answer. The explorer link still works.',
          unconfirmed: 'No final status yet. The explorer link has the full record.',
          now: 'Test network slot now',
          later: 'Slots since',
        },
      },
      status: 'Test payments today.',
      next: 'Next: the network fee paid for you, so friends never need SOL, and a listing on the Solana dApp Store.',
    },
    proof: {
      head: 'See it run.',
      line: 'Recorded on a test phone running build 17. Test contacts, an invented place, test funds.',
      play: 'Play the demo (2:19)',
      stats: [
        { n: '8,000+', t: 'automated tests' },
        { n: '0', t: 'phone numbers needed to sign up' },
      ],
      links: { github: 'GitHub', chain: 'The transaction' },
      close: 'Close',
    },
    get: {
      head: 'Get it on your phone.',
      line: 'Android 10 or newer. Payments use Solana test funds, never real money.',
      button: 'Download the APK',
      scan: 'Scan with your Android phone',
      sha: 'SHA-256',
      copy: 'Copy',
      copied: 'Copied',
      steps: [
        'Open the link on the phone itself.',
        'Android asks to allow installs from your browser. Allow it once.',
        'Install, open, and make your profile. No phone number needed.',
      ],
      more: 'All downloads and checksums',
      next: 'Next: group calls, mainnet after an audit, the first real places.',
    },
    foot: {
      made: 'The phone on this page is modeled and rendered by us in Blender.',
      screens: 'Its screens are real recordings of build 17, with test contacts.',
      download: 'Download',
      pricing: 'Pricing',
      account: 'Account',
      security: 'Security',
      legal: 'Legal',
      github: 'GitHub',
      contact: 'Contact',
      lang: 'Українською',
    },
  },
  uk: {
    meta: {
      title: 'Nightpost: усе, для чого вам телефон, разом',
      description:
        'Чати, люди, моменти, плани, місця й гроші в одному застосунку для Android, з асистентом у всьому. Оплати йдуть через Solana.',
    },
    nav: { how: 'Як це працює', solana: 'Solana', demo: 'Демо', get: 'Завантажити', signIn: 'Увійти', menu: 'Меню', close: 'Закрити' },
    hero: {
      a: 'Усе, для чого вам телефон,',
      b: 'разом.',
      sub: 'Чати, люди, моменти, плани, місця й гроші в одному застосунку, з асистентом у всьому. Оплати йдуть через Solana.',
      get: 'Завантажити для Android',
      getNote: 'APK',
      watch: 'Дивитись демо',
      watchNote: '2:19',
      status: ['Android 10+', 'Тестові платежі на Solana'],
      chapters: ['Сьогодні', 'Люди', 'Розмова', 'Моменти', 'Плани', 'Місця', 'Поділ', 'Асистент', 'Гаманець', 'Ваш вигляд'],
      apps: ['Месенджер', 'Мапи', 'Календар', 'Фото', 'Банк', 'Ділилка'],
      pause: 'Пауза',
      ios: 'Поки що лише Android. На iPhone подивись демо.',
      play: 'Грати',
    },
    people: {
      eyebrow: 'Люди',
      head: 'Ваші люди — за тим, наскільки близькі.',
      line: 'Чати без номера телефону, групи до 32, голосові й дзвінки. Найважливіші люди — першими, а момент бачать лише ті, кого ви обрали.',
      chips: ['Близькі', 'Друзі', 'Робота', 'Усі'],
    },
    flow: {
      eyebrow: 'Плани, місця, гроші',
      head: 'Від «ходімо поїмо» до розрахунку.',
      stages: [
        { name: 'План', head: 'Хто йде — просто в чаті.', line: 'Картка зустрічі: місце, час і хто йде. Один дотик — «Іду».' },
        { name: 'Місце', head: 'У місця — своя сторінка.', line: 'Години й меню задає той, хто веде заклад. Зустріч там — одним дотиком.' },
        { name: 'Поділ', head: 'Ділиш за тим, що їв.', line: 'Кожен позначає свої страви й платить свою частку в USDC.' },
      ],
    },
    ask: {
      eyebrow: 'Асистент',
      head: 'Попросіть раз. Підтверджуєте ви.',
      prompt: 'Надішли 5 USDC Даніелю',
      line: 'Асистент готує платіж. Нічого не піде, поки ви не підтвердите відбитком або PIN-кодом.',
    },
    look: {
      eyebrow: 'Ваш вигляд',
      head: 'Зроби своїм.',
      line: 'День, ніч чи за сонцем. Свій акцент і шпалери для кожного чату.',
      label: 'Тема',
      day: 'День',
      night: 'Ніч',
    },
    also: {
      head: 'Ще в застосунку',
      items: [
        'Мапа друзів, які самі вирішили ділитися місцем, до заданого часу',
        'Повідомлення наскрізно зашифровані',
        'Дзвінок з будь-якого чату одним дотиком',
        'Збережені повідомлення, завжди вгорі',
      ],
    },
    sol: {
      eyebrow: 'На Solana',
      head: 'Гроші йдуть як повідомлення.',
      line: 'Оплати між друзями малі, часті й живуть у розмові. Їм потрібен переказ, що проходить за секунди, коштує частку цента й підписується на телефоні.',
      facts: [
        { head: 'Ви платите людині, а не адресі.', line: 'USDC, EURC і SOL просто з чату, тому, кого знаєте.' },
        {
          head: 'Мережа показує, хто заплатив.',
          line: 'Кожна частка чека — окремий TransferChecked з ідентифікатором чека в SPL Memo.',
        },
        { head: 'Ключі лишаються на телефоні.', line: 'Android Keystore. На Seeker — Mobile Wallet Adapter і Seed Vault.' },
        { head: 'Впізнаємо Seeker.', line: 'Genesis Token і рівень стейку SKR — знак у профілі, лише для читання.' },
      ],
      receipt: {
        title: 'Частка чека, надіслана з чату',
        net: 'Solana, тест',
        rows: [
          ['Сума', '25 USDC'],
          ['Від', 'vqNi…7JD2'],
          ['Кому', '2ZWP…oA8g'],
          ['Інструкції', 'Створення токен-рахунку · TransferChecked · Memo'],
          ['Комісія мережі', '0,000005 SOL'],
          ['Слот', '509 953 198'],
          ['Час', '11 жовтня 2026, 15:51 UTC'],
        ],
        memo: 'nightpost-bill:v1:1421ac56-95ab-4d2a-94af-a264bb1d73c6',
        open: 'Відкрити в Solana Explorer',
        foot: 'Справжня транзакція в тестовій мережі, зроблена із застосунку тестовими грошима.',
        confirm: 'Підтверджено на телефоні',
        live: {
          head: 'Наживо з тестової мережі',
          state: 'Стан',
          checking: 'Питаємо тестову мережу…',
          finalized: 'Остаточна',
          unknown: 'Тестова мережа не відповіла. Посилання на explorer працює.',
          unconfirmed: 'Остаточного статусу ще немає. Повний запис — за посиланням на explorer.',
          now: 'Слот тестової мережі зараз',
          later: 'Слотів відтоді',
        },
      },
      status: 'Сьогодні тестові платежі.',
      next: 'Далі: комісію мережі платимо за вас, щоб друзям не був потрібен SOL, і сторінка в Solana dApp Store.',
    },
    proof: {
      head: 'Подивись, як це працює.',
      line: 'Записано на тестовому телефоні зі збіркою 17. Тестові контакти, вигадане місце, тестові гроші.',
      play: 'Увімкнути демо (2:19)',
      stats: [
        { n: '8 000+', t: 'автоматичних тестів' },
        { n: '0', t: 'номерів телефону для реєстрації' },
      ],
      links: { github: 'GitHub', chain: 'Транзакція' },
      close: 'Закрити',
    },
    get: {
      head: 'Поставте на свій телефон.',
      line: 'Android 10 або новіший. Оплати — тестові гроші Solana, ніколи не справжні.',
      button: 'Завантажити APK',
      scan: 'Скануйте телефоном з Android',
      sha: 'SHA-256',
      copy: 'Копіювати',
      copied: 'Скопійовано',
      steps: [
        'Відкрийте посилання на самому телефоні.',
        'Android попросить дозволити встановлення з браузера. Дозволь один раз.',
        'Встановіть, відкрийте і створіть профіль. Номер телефону не потрібен.',
      ],
      more: 'Усі завантаження й контрольні суми',
      next: 'Далі: групові дзвінки, mainnet після аудиту, перші справжні місця.',
    },
    foot: {
      made: 'Телефон на цій сторінці ми змоделювали й відрендерили в Blender.',
      screens: 'На його екрані справжні записи збірки 17 з тестовими контактами.',
      download: 'Завантажити',
      pricing: 'Ціни',
      account: 'Кабінет',
      security: 'Безпека',
      legal: 'Умови',
      github: 'GitHub',
      contact: 'Звʼязок',
      lang: 'English',
    },
  },
};
