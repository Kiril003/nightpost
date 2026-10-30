import { FREE_UNTIL, PRICES, euro } from '../lib/pricing.ts';
import type { Lang } from './types';

export const SCREENS = [
  'today',
  'chat',
  'profile',
  'map',
  'reminders',
  'money-order',
  'wallet2',
  'page-shop',
  'place-shifts',
  'assistant',
] as const;
export type Screen = (typeof SCREENS)[number];

/** Заголовок з одним курсивним словом: [до, курсив, після]. */
export type Rich = readonly [string, string, string];

export type PartId = 'talk' | 'people' | 'map' | 'day' | 'money' | 'places' | 'assistant';

export interface WowPart {
  id: PartId;
  no: string;
  name: string;
  rich: Rich;
  short: string;
  right: string;
  toc: string;
  title: Rich;
  lead: string;
  leadShort: string;
  facts: readonly (readonly [string, string])[];
  cards: readonly (readonly [string, string])[];
  screens: readonly Screen[];
}

export interface WowCopy {
  meta: { title: string; description: string };
  nav: {
    home: string;
    product: string;
    builder: string;
    pricing: string;
    download: string;
    getApp: string;
    menu: string;
    toDay: string;
    toNight: string;
  };
  foot: {
    tagline: string;
    product: string;
    overview: string;
    trust: string;
    security: string;
    privacy: string;
    terms: string;
    deleteAccount: string;
    contact: string;
    language: string;
    devnet: string;
  };
  hero: {
    title: Rich;
    lead: string;
    leadShort: string;
    cta: string;
    ctaMobile: string;
    inside: string;
    facts: readonly string[];
    sample: string;
  };
  contents: { title: Rich; lead: string };
  parts: readonly WowPart[];
  receipt: {
    head: string;
    status: string;
    net: string;
    labels: { instructions: string; fee: string; slot: string; time: string; signature: string };
    time: string;
    explorer: string;
    note: string;
  };
  assistant: {
    quote: Rich;
    captured: string;
    recreated: string;
    demo: string;
    sampleNote: string;
    pillars: readonly (readonly [string, string, string])[];
  };
  numbers: { fee: string; sales: string; levels: string; file: string; mb: string };
  end: {
    title: Rich;
    lead: string;
    more: string;
    steps: readonly [string, string, string];
    notYet: string;
    scan: string;
    computer: string;
  };
  download: {
    meta: { title: string; description: string };
    title: string;
    titleRich: Rich;
    lead: string;
    leadShort: string;
    steps: readonly (readonly [string, string])[];
    handoff: { title: string; text: string; computer: string; qrAlt: string };
    available: string;
    notYet: string;
    file: string;
    fine: string;
    ways: { title: string; titleRich: Rich; lead: string; leadShort: string };
    rows: readonly (readonly [string, string])[];
    others: string;
    checks: readonly (readonly [string, string, string])[];
    onWindows: string;
    help: { title: string; titleRich: Rich; write: string; tail: string };
  };
  alt: Record<Screen, string>;
}

const en: WowCopy = {
  meta: {
    title: 'Nightpost — everything your phone is for, joined',
    description:
      'Talk, your people in circles, the map, your day, money and places. One Android app, with an assistant across all of it. Payments settle on Solana.',
  },
  nav: {
    home: 'Nightpost home',
    product: 'Product',
    builder: 'Builder',
    pricing: 'Pricing',
    download: 'Download',
    getApp: 'Get the app',
    menu: 'Menu',
    toDay: 'Switch to Day',
    toNight: 'Switch to Night',
  },
  foot: {
    tagline: 'Everything your phone is for, joined. Payments settle on Solana.',
    product: 'Product',
    overview: 'Overview',
    trust: 'Trust',
    security: 'Security',
    privacy: 'Privacy',
    terms: 'Terms',
    deleteAccount: 'Delete account',
    contact: 'Contact',
    language: 'Language',
    devnet: 'Payments settle on Solana. Test funds today.',
  },
  hero: {
    title: ['Everything your phone is for, ', 'joined.', ''],
    lead: 'Talk, your people in circles, the map, your day, money and places. One Android app, with an assistant across all of it.',
    leadShort: 'Talk, people in circles, the map, your day, money and places. One Android app, with an assistant across all of it.',
    cta: 'Get the Android app',
    ctaMobile: 'Download for Android',
    inside: 'See what’s inside',
    facts: ['Free', 'Android 10 or newer', 'Payments on Solana, test funds'],
    sample: 'Sample data',
  },
  contents: {
    title: ['Seven things a phone is for. ', 'One', ' place.'],
    lead: 'Each part knows the others. A chat can hold a plan, a payment or a place, and the assistant works across all of them.',
  },
  parts: [
    {
      id: 'talk',
      no: '01',
      name: 'Talk and share',
      rich: ['Talk and share', '', ''],
      short: 'Talk',
      right: 'Chats · groups · channels · calls',
      toc: 'Chats, groups, channels, voice and video calls.',
      title: ['A message can be ', 'a plan.', ''],
      lead: 'Say “dinner at seven?” and the chat holds the place, the time and who’s in. Nobody scrolls back to find it.',
      leadShort: 'Say “dinner at seven?” and the chat holds the place, the time and who’s in.',
      facts: [
        ['Chats, groups and channels', 'Text, voice, photos, files'],
        ['Voice and video calls', 'From any chat'],
        ['Meetups in the chat', 'Place · time · I’m in'],
      ],
      cards: [
        ['Meetups', 'Place, time, I’m in'],
        ['Calls', 'Voice and video, from any chat'],
        ['Files', 'Voice notes, photos, documents'],
      ],
      screens: ['chat'],
    },
    {
      id: 'people',
      no: '02',
      name: 'People in circles',
      rich: ['People in ', 'circles', ''],
      short: 'People',
      right: 'No phone number needed',
      toc: 'Close, friends, work. Each sees what you choose.',
      title: ['Your people, by how ', 'close.', ''],
      lead: 'Put each person in a circle. Each circle sees only what you choose: where you are, your moments, your status.',
      leadShort: 'Each circle sees only what you choose: where you are, your moments, your status.',
      facts: [
        ['Close', ''],
        ['Friends', ''],
        ['Work', ''],
      ],
      cards: [
        ['Circles', 'Close, friends, work'],
        ['No phone number', 'Find people by name or code'],
        ['See as', 'Check what a circle sees'],
      ],
      screens: ['profile'],
    },
    {
      id: 'map',
      no: '03',
      name: 'The map',
      rich: ['The map', '', ''],
      short: 'Map',
      right: 'Places and your people',
      toc: 'Places, and your people when they share where they are.',
      title: ['Near, ', 'as near', ' as they allow.'],
      lead: 'Your people show on the map only when they share. They choose how exact, and for how long.',
      leadShort: 'People show on the map only when they share. They pick how exact, and for how long.',
      facts: [
        ['District, block or exact', 'They choose'],
        ['15 minutes, an hour, until they stop', 'Then it ends'],
        ['Message, wave, meet halfway', 'From the map'],
      ],
      cards: [
        ['District, block, exact', 'They choose'],
        ['15 min, 1 hour, until stopped', 'Then it ends'],
        ['Meet halfway', 'From the map'],
      ],
      screens: ['map'],
    },
    {
      id: 'day',
      no: '04',
      name: 'Your day',
      rich: ['Your day', '', ''],
      short: 'Day',
      right: 'Plans · reminders · calendar',
      toc: 'Reminders, plans you said yes to, your calendar.',
      title: ['What’s next, in ', 'one', ' list.'],
      lead: 'Reminders, plans you said yes to and your phone calendar, in order. A reminder to pay someone back has a Pay button.',
      leadShort: 'Reminders, plans you said yes to and your phone calendar, in order.',
      facts: [
        ['Reminders', 'Once, weekly, important'],
        ['Plans from chats', 'Who’s going, where'],
        ['Your phone calendar', 'Read-only, if you allow it'],
      ],
      cards: [
        ['Reminders', 'Once, weekly, important'],
        ['Plans from chats', 'Who’s going, where'],
        ['Pay back', 'A reminder with a Pay button'],
      ],
      screens: ['reminders'],
    },
    {
      id: 'money',
      no: '05',
      name: 'Money, points and gifts',
      rich: ['Money, points, gifts', '', ''],
      short: 'Money',
      right: 'Settles on Solana',
      toc: 'Pay a person, split a bill. Settles on Solana.',
      title: ['Money moves like ', 'a message.', ''],
      lead: 'Pay a person, not an address. Split a bill in the group, and everyone ticks their own dishes.',
      leadShort:
        'Pay a person, not an address. Split a bill and everyone ticks their own dishes. Places give points and gifts to people who come back.',
      facts: [
        ['USDC, EURC and SOL', 'Sent from the chat'],
        ['One transfer per share', 'The chain shows who paid'],
        ['Keys stay on the phone', 'Android Keystore'],
      ],
      cards: [],
      screens: ['money-order', 'wallet2'],
    },
    {
      id: 'places',
      no: '06',
      name: 'Places and Builder',
      rich: ['Places and Builder', '', ''],
      short: 'Places',
      right: 'For people, studios, cafés, teams',
      toc: 'A page for a person, a studio or a café, with orders in chat.',
      title: ['A place gets a page. ', 'An order', ' becomes a chat.'],
      lead: 'Builder makes a page from blocks: menu, hours, shop, bookings. Orders arrive in chats, and the team’s shifts live next to them.',
      leadShort: 'Builder makes a page from blocks: menu, hours, shop, bookings. Orders arrive in chats.',
      facts: [
        [`Builder from ${euro(PRICES.free)}`, `Free until ${FREE_UNTIL.en}`],
        [`${PRICES.free}% on sales`, 'Paid to your wallet'],
        ['Shifts and swaps', 'Clock in on arrival'],
      ],
      cards: [],
      screens: ['page-shop', 'place-shifts'],
    },
    {
      id: 'assistant',
      no: '07',
      name: 'The assistant',
      rich: ['The assistant', '', ''],
      short: 'Assistant',
      right: 'Across every part',
      toc: 'Drafts replies, plans and payments. You confirm.',
      title: ['Ask once. ', 'You', ' confirm.'],
      lead: 'Type “send Olena 0.05 SOL” and it drafts the payment. Nothing leaves until you confirm with your fingerprint.',
      leadShort: 'It drafts replies, plans and payments. Nothing leaves until you confirm.',
      facts: [
        ['One bar for everything', 'Search or ask, at the top of Chats'],
        ['Drafts replies, plans, payments', 'You see all of it first'],
        ['Never sends money by itself', 'Fingerprint to confirm'],
      ],
      cards: [],
      screens: ['assistant'],
    },
  ],
  receipt: {
    head: 'A bill share, sent from the chat',
    status: 'Finalized',
    net: 'Solana · test funds',
    labels: { instructions: 'Instructions', fee: 'Network fee', slot: 'Slot', time: 'Time', signature: 'Signature' },
    time: '11 Oct 2026, 15:51 UTC',
    explorer: 'Open in Solana Explorer',
    note: 'A real transaction with test funds, made from the app.',
  },
  assistant: {
    quote: ['It drafts. ', 'You', ' decide.'],
    captured: 'Captured on a test phone · test funds',
    recreated: 'Recreated from a test-phone capture · test funds',
    demo: 'Watch the demo',
    sampleNote: 'Other phones on this page show sample data, no real people.',
    pillars: [
      ['One bar', 'Search or ask', 'People, chats, places, or a question. The same bar at the top of Chats.'],
      ['Drafts', 'A reply, a plan, a payment', 'You see the whole draft before anything happens.'],
      ['Your call', 'Nothing leaves without you', 'Payments wait for your fingerprint. It never sends money by itself.'],
    ],
  },
  numbers: {
    fee: 'Network fee on a real bill share with test funds, sent from the app.',
    sales: 'Taken from sales on Builder pages. Paid straight to the wallet.',
    levels: 'Levels of where you are: district, block, exact. Your people see only the one you pick.',
    file: 'The whole app, one file, straight from our server with its checksum.',
    mb: 'MB',
  },
  end: {
    title: ['Get it on your ', 'phone.', ''],
    lead: 'Free. Sign in by email, and the file comes straight from our server, with its size and checksum.',
    more: 'All ways to install',
    steps: ['Open this page on the phone', 'Sign in and allow installs', 'Open Nightpost'],
    notYet: 'Google Play and the Solana dApp Store: not yet.',
    scan: 'Scan with your Android phone',
    computer: 'On a computer? Scan with your Android phone.',
  },
  download: {
    meta: {
      title: 'Download Nightpost — the Android file',
      description:
        'The Android file, straight from our server after you sign in by email, with build, size and checksum. Google Play and the Solana dApp Store: not yet.',
    },
    title: 'Download Nightpost.',
    titleRich: ['One file. ', 'Your phone.', ''],
    lead: 'One way works today: the Android file, straight from our server. Sign in by email, then download. No store, no phone number.',
    leadShort: 'The Android file, straight from our server, after you sign in by email.',
    steps: [
      ['Scan the code with your phone', 'It opens this page in the phone’s browser.'],
      [
        'Sign in by email, then tap Download',
        'Android asks to allow installs for any file from outside a store. You can turn it off again afterwards.',
      ],
      ['Install and open Nightpost', 'New versions install over the old one. Your chats stay.'],
    ],
    handoff: {
      title: 'Send it to your phone',
      text: 'Scan with the phone’s camera. This page opens there, where you sign in and install.',
      computer: 'On a computer? Scan with your Android phone to open this page there.',
      qrAlt: 'QR code to this page',
    },
    available: 'Available',
    notYet: 'Not yet',
    file: 'APK file · Android 10 or newer',
    fine: 'Build, size and checksum come from the server with the file.',
    ways: {
      title: 'Every way to install',
      titleRich: ['Every way to install, ', 'honestly.', ''],
      lead: 'Where each one stands today. When a store page exists, the link appears here.',
      leadShort: 'Where each one stands today',
    },
    rows: [
      ['APK file, directly', 'From our server after sign-in, with build, size and checksum.'],
      ['Google Play', 'A normal install, with updates through the store.'],
      ['Solana dApp Store', 'The store on Solana Mobile phones.'],
    ],
    others: 'Windows and Linux: in the works, no date yet. iPhone and macOS: not planned for now.',
    checks: [
      [
        'Check that the file is ours',
        'Two lines to compare',
        'Compute the file’s SHA-256 and compare it with the one in the card. One changed byte changes the whole line.',
      ],
      [
        'What downloads after you install',
        'Speech recognition, 137 MB',
        'The first time you use the microphone, the app fetches speech recognition, 137 MB.',
      ],
      [
        'How updates arrive',
        'Signed by us, checked by the app',
        'The app checks our server’s signature before it offers an update, and Android refuses a version signed by anyone else.',
      ],
    ],
    onWindows: 'On Windows:',
    help: {
      title: 'Something not working?',
      titleRich: ['Something not working? ', 'Write to us.', ''],
      write: 'Write to',
      tail: 'with your phone model and what you saw.',
    },
  },
  alt: {
    today: 'Chats: circles, people and the assistant in one list',
    chat: 'A group chat with a meetup card',
    map: 'The map with a person who shares where they are',
    wallet2: 'The wallet with recent payments',
    profile: 'A profile with circles',
    reminders: 'Reminders for today and tomorrow',
    'place-shifts': 'Shifts for a café team',
    'page-shop': 'A studio page made in Builder',
    'money-order': 'An order paid in the chat',
    assistant: 'The assistant drafts a payment and asks for a fingerprint',
  },
};

const uk: WowCopy = {
  meta: {
    title: 'Nightpost — усе, для чого вам телефон, разом',
    description:
      'Розмови, ваші люди в колах, мапа, ваш день, гроші й місця. Один застосунок для Android і помічник у всьому цьому. Платежі на Solana.',
  },
  nav: {
    home: 'Nightpost, головна',
    product: 'Продукт',
    builder: 'Builder',
    pricing: 'Ціни',
    download: 'Завантажити',
    getApp: 'Отримати застосунок',
    menu: 'Меню',
    toDay: 'Денна тема',
    toNight: 'Нічна тема',
  },
  foot: {
    tagline: 'Усе, для чого вам телефон, — разом. Платежі на Solana.',
    product: 'Продукт',
    overview: 'Огляд',
    trust: 'Довіра',
    security: 'Безпека',
    privacy: 'Приватність',
    terms: 'Умови',
    deleteAccount: 'Видалити акаунт',
    contact: 'Контакти',
    language: 'Мова',
    devnet: 'Платежі на Solana. Поки що тестові кошти.',
  },
  hero: {
    title: ['Усе, для чого вам телефон, — ', 'разом.', ''],
    lead: 'Розмови, ваші люди в колах, мапа, ваш день, гроші й місця. Один застосунок для Android і помічник у всьому цьому.',
    leadShort: 'Розмови, люди в колах, мапа, ваш день, гроші й місця. Один застосунок для Android і помічник у всьому цьому.',
    cta: 'Отримати для Android',
    ctaMobile: 'Завантажити для Android',
    inside: 'Що всередині',
    facts: ['Безкоштовно', 'Android 10 або новіший', 'Платежі на Solana, тестові кошти'],
    sample: 'Приклад даних',
  },
  contents: {
    title: ['Сім справ телефона. ', 'Одне', ' місце.'],
    lead: 'Кожна частина знає про інші. Чат може містити план, платіж чи місце, а помічник працює з усім цим.',
  },
  parts: [
    {
      id: 'talk',
      no: '01',
      name: 'Розмови й обмін',
      rich: ['Розмови й обмін', '', ''],
      short: 'Розмови',
      right: 'Чати · групи · канали · дзвінки',
      toc: 'Чати, групи, канали, голосові й відеодзвінки.',
      title: ['Повідомлення може бути ', 'планом.', ''],
      lead: 'Напишіть «вечеря о сьомій?» — і чат тримає місце, час і хто йде. Нікому не треба гортати назад.',
      leadShort: 'Напишіть «вечеря о сьомій?» — і чат тримає місце, час і хто йде.',
      facts: [
        ['Чати, групи й канали', 'Текст, голос, фото, файли'],
        ['Голосові й відеодзвінки', 'З будь-якого чату'],
        ['Зустрічі в чаті', 'Місце · час · Я йду'],
      ],
      cards: [
        ['Зустрічі', 'Місце, час, Я йду'],
        ['Дзвінки', 'Голос і відео з будь-якого чату'],
        ['Файли', 'Голосові, фото, документи'],
      ],
      screens: ['chat'],
    },
    {
      id: 'people',
      no: '02',
      name: 'Люди в колах',
      rich: ['Люди в ', 'колах', ''],
      short: 'Люди',
      right: 'Без номера телефону',
      toc: 'Близькі, друзі, робота. Кожне коло бачить те, що ви оберете.',
      title: ['Ваші люди, за ', 'близькістю.', ''],
      lead: 'Кожну людину — у своє коло. Кожне коло бачить лише те, що ви оберете: де ви, ваші моменти, ваш статус.',
      leadShort: 'Кожне коло бачить лише те, що ви оберете: де ви, ваші моменти, ваш статус.',
      facts: [
        ['Близькі', ''],
        ['Друзі', ''],
        ['Робота', ''],
      ],
      cards: [
        ['Кола', 'Близькі, друзі, робота'],
        ['Без номера телефону', 'Знаходьте людей за іменем або кодом'],
        ['Подивитися як', 'Перевірте, що бачить коло'],
      ],
      screens: ['profile'],
    },
    {
      id: 'map',
      no: '03',
      name: 'Мапа',
      rich: ['Мапа', '', ''],
      short: 'Мапа',
      right: 'Місця і ваші люди',
      toc: 'Місця — і ваші люди, коли діляться, де вони.',
      title: ['Поруч — ', 'настільки,', ' наскільки дозволять.'],
      lead: 'Ваші люди з’являються на мапі, лише коли самі діляться. Вони обирають, наскільки точно і як довго.',
      leadShort: 'Люди з’являються на мапі, лише коли діляться. Вони обирають, наскільки точно і як довго.',
      facts: [
        ['Район, квартал або точно', 'Вирішують вони'],
        ['15 хвилин, година, поки не зупинять', 'Потім усе зникає'],
        ['Написати, помахати, зустрітися посередині', 'Просто з мапи'],
      ],
      cards: [
        ['Район, квартал, точно', 'Вирішують вони'],
        ['15 хв, 1 година, поки не зупинять', 'Потім усе зникає'],
        ['Зустрітися посередині', 'Просто з мапи'],
      ],
      screens: ['map'],
    },
    {
      id: 'day',
      no: '04',
      name: 'Ваш день',
      rich: ['Ваш день', '', ''],
      short: 'День',
      right: 'Плани · нагадування · календар',
      toc: 'Нагадування, погоджені плани, ваш календар.',
      title: ['Що далі — ', 'одним', ' списком.'],
      lead: 'Нагадування, погоджені плани й календар телефону — по порядку. У нагадуванні повернути борг є кнопка «Оплатити».',
      leadShort: 'Нагадування, погоджені плани й календар телефону — по порядку.',
      facts: [
        ['Нагадування', 'Раз, щотижня, важливі'],
        ['Плани з чатів', 'Хто йде і куди'],
        ['Календар телефону', 'Лише читання, якщо дозволите'],
      ],
      cards: [
        ['Нагадування', 'Раз, щотижня, важливі'],
        ['Плани з чатів', 'Хто йде і куди'],
        ['Повернути борг', 'Нагадування з кнопкою «Оплатити»'],
      ],
      screens: ['reminders'],
    },
    {
      id: 'money',
      no: '05',
      name: 'Гроші, бали й подарунки',
      rich: ['Гроші, бали, подарунки', '', ''],
      short: 'Гроші',
      right: 'Розрахунок на Solana',
      toc: 'Платіть людині, діліть рахунок. Розрахунок на Solana.',
      title: ['Гроші ходять, як ', 'повідомлення.', ''],
      lead: 'Ви платите людині, а не адресі. Розділіть рахунок у групі — і кожен позначить свої страви.',
      leadShort:
        'Ви платите людині, а не адресі. Розділіть рахунок — і кожен позначить свої страви. Місця дарують бали й подарунки тим, хто повертається.',
      facts: [
        ['USDC, EURC і SOL', 'Прямо з чату'],
        ['Один переказ на частку', 'Мережа показує, хто заплатив'],
        ['Ключі лишаються на телефоні', 'Android Keystore'],
      ],
      cards: [],
      screens: ['money-order', 'wallet2'],
    },
    {
      id: 'places',
      no: '06',
      name: 'Місця і Builder',
      rich: ['Місця і Builder', '', ''],
      short: 'Місця',
      right: 'Для людей, студій, кав’ярень, команд',
      toc: 'Сторінка для людини, студії чи кав’ярні, із замовленнями в чаті.',
      title: ['У місця — своя сторінка. ', 'Замовлення', ' — це чат.'],
      lead: 'Builder складає сторінку з блоків: меню, години, крамниця, бронювання. Замовлення приходять у чати, а зміни команди — поруч.',
      leadShort: 'Builder складає сторінку з блоків: меню, години, крамниця, бронювання. Замовлення приходять у чати.',
      facts: [
        [`Builder від ${euro(PRICES.free)}`, `Безкоштовно до ${FREE_UNTIL.uk}`],
        [`${PRICES.free}% з продажів`, 'Одразу на ваш гаманець'],
        ['Зміни й обміни', 'Відмітка на місці'],
      ],
      cards: [],
      screens: ['page-shop', 'place-shifts'],
    },
    {
      id: 'assistant',
      no: '07',
      name: 'Помічник',
      rich: ['Помічник', '', ''],
      short: 'Помічник',
      right: 'У кожній частині',
      toc: 'Готує відповіді, плани й платежі. Ви підтверджуєте.',
      title: ['Попросіть раз. ', 'Підтверджуєте', ' ви.'],
      lead: 'Напишіть «send Olena 0.05 SOL» — і він підготує платіж. Нічого не піде, поки ви не підтвердите відбитком пальця.',
      leadShort: 'Готує відповіді, плани й платежі. Нічого не піде, поки ви не підтвердите.',
      facts: [
        ['Один рядок для всього', 'Пошук або питання, вгорі Чатів'],
        ['Готує відповіді, плани, платежі', 'Ви бачите все спершу'],
        ['Сам гроші не надсилає', 'Підтвердження відбитком'],
      ],
      cards: [],
      screens: ['assistant'],
    },
  ],
  receipt: {
    head: 'Частка рахунку, надіслана з чату',
    status: 'Підтверджено',
    net: 'Solana · тестові кошти',
    labels: { instructions: 'Інструкції', fee: 'Комісія мережі', slot: 'Слот', time: 'Час', signature: 'Підпис' },
    time: '11 жовт. 2026, 15:51 UTC',
    explorer: 'Відкрити в Solana Explorer',
    note: 'Справжня транзакція тестовими коштами, зроблена із застосунку.',
  },
  assistant: {
    quote: ['Він готує. ', 'Ви', ' вирішуєте.'],
    captured: 'Знято на тестовому телефоні · тестові кошти',
    recreated: 'Відтворено із запису на тестовому телефоні · тестові кошти',
    demo: 'Дивитися демо',
    sampleNote: 'Інші телефони на сторінці показують приклад даних, без справжніх людей.',
    pillars: [
      ['Один рядок', 'Шукайте або питайте', 'Люди, чати, місця чи питання. Той самий рядок угорі Чатів.'],
      ['Чернетки', 'Відповідь, план, платіж', 'Ви бачите всю чернетку, перш ніж щось станеться.'],
      ['Ваше рішення', 'Без вас нічого не піде', 'Платежі чекають на ваш відбиток. Сам він гроші не надсилає.'],
    ],
  },
  numbers: {
    fee: 'Комісія мережі за справжню частку рахунку тестовими коштами, надіслану із застосунку.',
    sales: 'Комісія з продажів на сторінках Builder. Гроші йдуть просто на гаманець.',
    levels: 'Рівні того, де ви: район, квартал, точно. Ваші люди бачать лише той, що ви оберете.',
    file: 'Увесь застосунок одним файлом, просто з нашого сервера, з контрольною сумою.',
    mb: 'МБ',
  },
  end: {
    title: ['Встановіть на свій ', 'телефон.', ''],
    lead: 'Безкоштовно. Увійдіть поштою — і файл прийде просто з нашого сервера, з розміром і контрольною сумою.',
    more: 'Усі способи встановити',
    steps: ['Відкрийте цю сторінку на телефоні', 'Увійдіть і дозвольте встановлення', 'Відкрийте Nightpost'],
    notYet: 'Google Play і Solana dApp Store: поки ні.',
    scan: 'Скануйте телефоном з Android',
    computer: 'За комп’ютером? Скануйте телефоном з Android.',
  },
  download: {
    meta: {
      title: 'Завантажити Nightpost — файл для Android',
      description:
        'Файл для Android просто з нашого сервера після входу поштою, зі збіркою, розміром і контрольною сумою. Google Play і Solana dApp Store: поки ні.',
    },
    title: 'Завантажити Nightpost.',
    titleRich: ['Один файл. ', 'Ваш телефон.', ''],
    lead: 'Сьогодні працює один спосіб: файл для Android просто з нашого сервера. Увійдіть поштою — і завантажуйте. Без магазину й номера телефону.',
    leadShort: 'Файл для Android просто з нашого сервера, після входу поштою.',
    steps: [
      ['Скануйте код телефоном', 'Код відкриє цю сторінку в браузері телефона.'],
      [
        'Увійдіть поштою й натисніть «Завантажити»',
        'Android питає дозволу на встановлення для будь-якого файлу не з магазину. Потім це можна знову вимкнути.',
      ],
      ['Встановіть і відкрийте Nightpost', 'Нові версії ставляться поверх старої. Чати лишаються.'],
    ],
    handoff: {
      title: 'Надішліть на телефон',
      text: 'Скануйте камерою телефона. Ця сторінка відкриється там, де ви ввійдете й установите застосунок.',
      computer: 'За комп’ютером? Скануйте телефоном з Android, щоб відкрити цю сторінку там.',
      qrAlt: 'QR-код на цю сторінку',
    },
    available: 'Доступно',
    notYet: 'Поки ні',
    file: 'Файл APK · Android 10 або новіший',
    fine: 'Збірка, розмір і контрольна сума приходять із сервера разом із файлом.',
    ways: {
      title: 'Усі способи встановити',
      titleRich: ['Усі способи встановити, ', 'чесно.', ''],
      lead: 'Де кожен із них сьогодні. Коли з’явиться сторінка в магазині, посилання буде тут.',
      leadShort: 'Де кожен із них сьогодні',
    },
    rows: [
      ['Файл APK напряму', 'З нашого сервера після входу, зі збіркою, розміром і контрольною сумою.'],
      ['Google Play', 'Звичайне встановлення, оновлення через магазин.'],
      ['Solana dApp Store', 'Магазин на телефонах Solana Mobile.'],
    ],
    others: 'Windows і Linux: у роботі, дати поки немає. iPhone і macOS: поки не плануємо.',
    checks: [
      [
        'Перевірте, що файл наш',
        'Два рядки для порівняння',
        'Порахуйте SHA-256 файлу й порівняйте з тим, що в картці. Один змінений байт змінює весь рядок.',
      ],
      [
        'Що докачується після встановлення',
        'Розпізнавання мовлення, 137 МБ',
        'Коли ви вперше скористаєтеся мікрофоном, застосунок докачає розпізнавання мовлення, 137 МБ.',
      ],
      [
        'Як приходять оновлення',
        'Підписані нами, перевірені застосунком',
        'Застосунок перевіряє підпис нашого сервера, перш ніж запропонувати оновлення, а Android не прийме версію з чужим підписом.',
      ],
    ],
    onWindows: 'У Windows:',
    help: {
      title: 'Щось не працює?',
      titleRich: ['Щось не працює? ', 'Напишіть нам.', ''],
      write: 'Напишіть на',
      tail: 'і вкажіть модель телефона та що сталося.',
    },
  },
  alt: {
    today: 'Чати: кола, люди й помічник в одному списку',
    chat: 'Груповий чат із карткою зустрічі',
    map: 'Мапа з людиною, яка ділиться, де вона',
    wallet2: 'Гаманець з останніми платежами',
    profile: 'Профіль із колами',
    reminders: 'Нагадування на сьогодні й завтра',
    'place-shifts': 'Зміни команди кав’ярні',
    'page-shop': 'Сторінка студії, зроблена в Builder',
    'money-order': 'Замовлення, оплачене в чаті',
    assistant: 'Помічник готує платіж і просить відбиток',
  },
};

export const wowCopy: Record<Lang, WowCopy> = { en, uk };

/** Знімок помічника знято лише англійською вдень; решта відтворена з того запису. */
export function assistantCaption(lang: Lang, theme: 'day' | 'night'): string {
  const a = wowCopy[lang].assistant;
  return lang === 'en' && theme === 'day' ? a.captured : a.recreated;
}
