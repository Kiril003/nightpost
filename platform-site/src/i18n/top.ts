import type { Lang } from './types';
import type { Rich } from './wow';
import { FREE_UNTIL } from '../lib/pricing.ts';

export type ChapterId = 'talk' | 'people' | 'map' | 'day' | 'money';

export interface TopChapter {
  id: ChapterId;
  kick: string;
  title: Rich;
  body: string;
  facts: readonly (readonly [string, string])[];
  ask: string;
  answer: string;
  alt: readonly string[];
}

export interface TopCopy {
  meta: { title: string; description: string };
  hero: { title: Rich; lede: string; cta: string; scan: readonly [string, string]; facts: string; alt: string };
  ask: string;
  chapters: readonly TopChapter[];
  proof: {
    title: Rich;
    lead: string;
    app: { k: string; h: string; p: string; link: string };
    tx: { k: string; unit: string; labels: readonly [string, string, string, string]; time: string; explorer: string; fine: string };
    builder: { k: string; h: string; p: string; link: string };
  };
  get: { title: Rich; cta: string; qrAlt: string; steps: readonly [string, string, string] };
}

/** Справжній переказ із застосунку (тестова мережа), звірений через RPC: слот, час, комісія. */
export const PROOF_TX = {
  signature: 'RkJ9GoQ5jSyvF86BjHWmaVZ38NRkk6Tb9brBHsgP72F69YaG6crDJR27VEKL7Xe3s5zZ257VQbWY2mfsfQQP8wC',
  amount: '25',
  instructions: 'TransferChecked · Memo',
  fee: '0.000005 SOL',
  slot: '509,953,198',
} as const;

const en: TopCopy = {
  meta: {
    title: 'Nightpost — everything your phone is for, joined',
    description: 'Talk, people, the map, your day, money and places, in one Android app, with an assistant across it.',
  },
  hero: {
    title: ['Everything your phone is for, ', 'joined.', ''],
    lede: 'Talk, people, the map, your day, money and places, in one Android app, with an assistant across it.',
    cta: 'Get the Android app',
    scan: ['Or scan', 'with your phone'],
    facts: 'Free · Android 10 or newer · No phone number',
    alt: 'Nightpost on Android: the Chats screen with circles All, Close, Friends and Work',
  },
  ask: 'Ask',
  chapters: [
    {
      id: 'talk',
      kick: 'Talk and share',
      title: ['A chat can hold ', 'the plan.', ''],
      body: 'Messages, voice, calls and groups. A meetup card keeps the place, the time and who is coming.',
      facts: [
        ['Meetups', 'Place, time, who’s in. One tap: I’m in.'],
        ['Calls', 'Voice and video, from any chat.'],
        ['Files', 'Photos, voice notes, documents.'],
      ],
      ask: 'Dinner on Friday at 7 with Sofia',
      answer: 'A meetup card, drafted. You send it.',
      alt: ['A group chat with a meetup card: Lantern Kitchen, today 19:00, 3 of 4 going'],
    },
    {
      id: 'people',
      kick: 'People in circles',
      title: ['Your people, ', 'by how close.', ''],
      body: 'Close, Friends, Work, or your own. Each circle sees only what you choose: your place, your moments, your status.',
      facts: [
        ['Circles', 'Personal, close, friends, social, work.'],
        ['No phone number', 'Find people by name or a code.'],
        ['See as', 'Check what a circle sees before you share.'],
      ],
      ask: 'Olena',
      answer: 'People, chats and places from one bar.',
      alt: ['A profile with circles Close, Friends and Work'],
    },
    {
      id: 'map',
      kick: 'The map',
      title: ['Near, ', 'as near as they allow.', ''],
      body: 'People show on the map only when they share, for as long as they choose. Places and coffee nearby, and a spot halfway between you.',
      facts: [
        ['Sharing', '15 minutes, an hour, or until you stop.'],
        ['How exact', 'District, block or exact. You choose.'],
        ['Meet halfway', 'A place between the two of you.'],
      ],
      ask: 'coffee',
      answer: 'Places around you, on the map.',
      alt: ['The map with Sofia 850 m away and buttons Message, Wave and Meet halfway'],
    },
    {
      id: 'day',
      kick: 'Your day',
      title: ['What’s next, ', 'above your chats.', ''],
      body: 'Reminders, plans you said yes to, and your phone calendar, in one sheet you pull down over Chats.',
      facts: [
        ['Reminders', 'At the time you set. A debt gets a Pay button.'],
        ['Plans', 'From the chats where you tapped I’m in.'],
        ['Calendar', 'Your phone calendar, in the same list.'],
      ],
      ask: 'Remind me to pay Taras back at 18:45',
      answer: 'A reminder, with Pay on it.',
      alt: ['The Day sheet: Pay Taras back €20.00 at 18:45, dinner at 19:00, yoga at 21:00'],
    },
    {
      id: 'money',
      kick: 'Money and places',
      title: ['A place gets a page. ', 'Money moves like a message.', ''],
      body: 'Builder gives a person, a studio or a café its own page, with orders in chat. Pay a person, not an address. Payments settle on Solana.',
      facts: [
        ['Builder', `Free until ${FREE_UNTIL.en}. 0% on sales.`],
        ['Wallet', 'USDC, EURC and SOL. Test funds today.'],
        ['Points and gifts', 'Places reward the people who come back.'],
      ],
      ask: 'Send Olena 0.05 SOL',
      answer: 'A draft. You confirm with your fingerprint or PIN.',
      alt: [
        'A studio page built with Builder: Small batches of everyday stoneware',
        'The wallet: €42.10 in EURC, marked Test, with recent payments',
      ],
    },
  ],
  proof: {
    title: ['Live ', 'today.', ''],
    lead: 'What works on Android now, and what is still a test.',
    app: {
      k: 'The app',
      h: 'On Android, free.',
      p: 'Chats, circles, the map, your day and the wallet work in today’s build. Android 10 or newer.',
      link: 'Download page',
    },
    tx: {
      k: 'A real transfer on Solana',
      unit: 'USDC',
      labels: ['Instructions', 'Network fee', 'Slot', 'Time'],
      time: '11 Oct 2026, 15:51 UTC',
      explorer: 'Open in Solana Explorer ↗',
      fine: 'Solana test network, test funds. Main network after an independent audit.',
    },
    builder: {
      k: 'Builder',
      h: 'Pages for places.',
      p: 'A person, a studio or a café builds a page from blocks, with orders that arrive as chats.',
      link: 'Open Builder',
    },
  },
  get: {
    title: ['Get it on ', 'your phone.', ''],
    cta: 'Download for Android',
    qrAlt: 'QR code: phantom-os.dev/download',
    steps: [
      'Scan, or open phantom-os.dev/download on your phone.',
      'Sign in by email, then tap Download.',
      'Install and open Nightpost. No phone number.',
    ],
  },
};

const uk: TopCopy = {
  meta: {
    title: 'Nightpost — усе, для чого вам телефон, разом',
    description: 'Розмови, люди, мапа, ваш день, гроші й місця в одному застосунку для Android, і помічник у всьому цьому.',
  },
  hero: {
    title: ['Усе, для чого вам телефон, — ', 'разом.', ''],
    lede: 'Розмови, люди, мапа, ваш день, гроші й місця в одному застосунку для Android, і помічник у всьому цьому.',
    cta: 'Отримати для Android',
    scan: ['Або скануйте', 'телефоном'],
    facts: 'Безкоштовно · Android 10 або новіший · Без номера телефону',
    alt: 'Nightpost на Android: екран Чатів із колами Усі, Близькі, Друзі й Робота',
  },
  ask: 'Спитайте',
  chapters: [
    {
      id: 'talk',
      kick: 'Розмови',
      title: ['Чат може містити ', 'план.', ''],
      body: 'Повідомлення, голосові, дзвінки й групи. Картка зустрічі тримає місце, час і те, хто прийде.',
      facts: [
        ['Зустрічі', 'Місце, час, хто йде. Один дотик: «Я йду».'],
        ['Дзвінки', 'Голос і відео з будь-якого чату.'],
        ['Файли', 'Фото, голосові, документи.'],
      ],
      ask: 'Вечеря в пʼятницю о 7 із Софією',
      answer: 'Картка зустрічі, чернетка. Надсилаєте ви.',
      alt: ['Груповий чат із карткою зустрічі: Lantern Kitchen, сьогодні 19:00, ідуть 3 із 4'],
    },
    {
      id: 'people',
      kick: 'Люди в колах',
      title: ['Ваші люди — ', 'за близькістю.', ''],
      body: 'Близькі, Друзі, Робота або ваші власні кола. Кожне коло бачить лише те, що ви оберете: де ви, ваші моменти, ваш статус.',
      facts: [
        ['Кола', 'Особисте, близькі, друзі, знайомі, робота.'],
        ['Без номера телефону', 'Знаходьте людей за імʼям або кодом.'],
        ['Як бачать', 'Перевірте, що бачить коло, перш ніж ділитися.'],
      ],
      ask: 'Олена',
      answer: 'Люди, чати й місця з одного рядка.',
      alt: ['Профіль із колами Близькі, Друзі й Робота'],
    },
    {
      id: 'map',
      kick: 'Мапа',
      title: ['Поруч — ', 'наскільки дозволять.', ''],
      body: 'Люди зʼявляються на мапі, лише коли діляться, і на стільки, на скільки самі оберуть. Місця й кава поблизу, і точка посередині між вами.',
      facts: [
        ['Ділитися', '15 хвилин, година або доки не вимкнете.'],
        ['Точність', 'Район, квартал або точно. Обираєте ви.'],
        ['Зустріч посередині', 'Місце між вами двома.'],
      ],
      ask: 'кава',
      answer: 'Місця навколо вас, на мапі.',
      alt: ['Мапа: Софія за 850 м і кнопки Написати, Помахати й Зустрітися посередині'],
    },
    {
      id: 'day',
      kick: 'Ваш день',
      title: ['Що далі — ', 'над вашими чатами.', ''],
      body: 'Нагадування, плани, на які ви погодились, і календар телефона в одному аркуші, який ви тягнете вниз над Чатами.',
      facts: [
        ['Нагадування', 'У час, який ви поставили. Борг отримує кнопку «Оплатити».'],
        ['Плани', 'З чатів, де ви натиснули «Я йду».'],
        ['Календар', 'Календар телефона в тому самому списку.'],
      ],
      ask: 'Нагадати повернути Тарасу гроші о 18:45',
      answer: 'Нагадування з кнопкою «Оплатити».',
      alt: ['Аркуш дня: повернути Тарасу €20.00 о 18:45, вечеря о 19:00, йога о 21:00'],
    },
    {
      id: 'money',
      kick: 'Гроші й місця',
      title: ['Місце отримує сторінку. ', 'Гроші йдуть, як повідомлення.', ''],
      body: 'Builder дає людині, студії чи кавʼярні власну сторінку із замовленнями в чаті. Платіть людині, а не адресі. Платежі проходять на Solana.',
      facts: [
        ['Builder', `Безкоштовно до ${FREE_UNTIL.uk}. 0% з продажів.`],
        ['Гаманець', 'USDC, EURC і SOL. Поки що тестові кошти.'],
        ['Бали й подарунки', 'Місця винагороджують тих, хто повертається.'],
      ],
      ask: 'Надіслати Олені 0.05 SOL',
      answer: 'Чернетка. Підтверджуєте відбитком пальця або PIN-кодом.',
      alt: [
        'Сторінка студії, зроблена в Builder: невеликі партії щоденної кераміки',
        'Гаманець: €42.10 в EURC з позначкою «Тест» і останніми платежами',
      ],
    },
  ],
  proof: {
    title: ['Працює ', 'сьогодні.', ''],
    lead: 'Що працює на Android уже зараз, а що поки тест.',
    app: {
      k: 'Застосунок',
      h: 'На Android, безкоштовно.',
      p: 'Чати, кола, мапа, ваш день і гаманець працюють у сьогоднішній збірці. Android 10 або новіший.',
      link: 'Сторінка завантаження',
    },
    tx: {
      k: 'Справжній переказ на Solana',
      unit: 'USDC',
      labels: ['Інструкції', 'Комісія мережі', 'Слот', 'Час'],
      time: '11 жовт. 2026, 15:51 UTC',
      explorer: 'Відкрити в Solana Explorer ↗',
      fine: 'Тестова мережа Solana, тестові кошти. Основна мережа — після незалежного аудиту.',
    },
    builder: {
      k: 'Builder',
      h: 'Сторінки для місць.',
      p: 'Людина, студія чи кавʼярня збирає сторінку з блоків, а замовлення приходять як чати.',
      link: 'Відкрити Builder',
    },
  },
  get: {
    title: ['Встановіть на ', 'свій телефон.', ''],
    cta: 'Завантажити для Android',
    qrAlt: 'QR-код: phantom-os.dev/download',
    steps: [
      'Скануйте або відкрийте phantom-os.dev/download на телефоні.',
      'Увійдіть поштою й натисніть «Завантажити».',
      'Встановіть і відкрийте Nightpost. Без номера телефону.',
    ],
  },
};

export const topCopy: Record<Lang, TopCopy> = { en, uk };
