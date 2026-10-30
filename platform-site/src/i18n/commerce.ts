import type { Lang, PageMeta } from './types';
import { FREE_UNTIL, PRICES, euro } from '../lib/pricing.ts';
import { OFFLINE_GRACE_DAYS, TRIAL_DAYS, type FeatureKey, type FeatureSurface, type PlanId } from '../lib/plans.ts';

const GRACE_DAYS = OFFLINE_GRACE_DAYS;

export interface PlanCopy {
  name: string;
  who: string;
  cta: string;
}

export interface FaqItem {
  q: string;
  a: string;
}

export interface PricingCopy {
  meta: PageMeta;
  eyebrow: string;
  title: string;
  lead: string;
  saleClosed: { title: string; body: string; mailNote: string };
  pledges: { title: string; desc: string }[];
  plansEyebrow: string;
  plansTitle: string;
  plans: Record<PlanId, PlanCopy>;
  devices: (n: number | null) => string;
  anyDevices: string;
  phoneCounts: string;
  updates: (years: number | null) => string;
  perpetual: string;
  everythingFree: string;
  scrollHint: string;
  notOnSale: string;
  compareEyebrow: string;
  compareTitle: string;
  compareLead: string;
  compareRowHead: string;
  compareDevices: string;
  compareUpdates: string;
  cellUnlimited: string;
  cellByAgreement: string;
  cellYears: (n: number) => string;
  features: Record<FeatureKey, string>;
  soon: string;
  included: string;
  notIncluded: string;
  surfaces: Record<FeatureSurface, string>;
  packsEyebrow: string;
  packsTitle: string;
  packsBody: string;
  packsLink: string;
  faqTitle: string;
  faq: FaqItem[];
  portalLink: string;
}

const FEATURES_UK: Record<FeatureKey, string> = {
  'core.chat': 'Листування',
  'core.map': 'Мапа',
  'core.nav': 'Навігація',
  'core.alerts': 'Тривоги й попередження',
  'core.vault': 'Збережене',
  'core.voice': 'Голос',
  'bridge.pair': 'Телефон разом із компʼютером',
  'map.terrain3d': 'Обʼємний рельєф і місто',
  'gnss.blackbox': 'Запис руху для розбору',
  'export.reports': 'Експорт звітів',
};

const FEATURES_EN: Record<FeatureKey, string> = {
  'core.chat': 'Messages',
  'core.map': 'Map',
  'core.nav': 'Navigation',
  'core.alerts': 'Alerts and warnings',
  'core.vault': 'Saved items',
  'core.voice': 'Voice',
  'bridge.pair': 'Phone and computer together',
  'map.terrain3d': '3D terrain and city',
  'gnss.blackbox': 'Movement recording for review',
  'export.reports': 'Report export',
};

const uk: PricingCopy = {
  meta: {
    title: 'Ціни — Nightpost',
    description:
      'Листування, мапа, навігація, тривоги, збережене й голос — безкоштовно назавжди. Платні рівні — безстрокова ліцензія з роками оновлень. Продаж ще не відкрито.',
  },
  eyebrow: 'Ціни',
  title: `Основа — безкоштовно. Плани Білдера від ${euro(PRICES.studio)} на місяць.`,
  lead:
    'Вільний рівень не має строку й не потребує ліцензії. Платні рівні додають пару з компʼютером, обʼємну мапу та звіти. Куплена версія працює й після завершення вікна оновлень.',
  saleClosed: {
    title: 'Поки нічого не стягується',
    body: `Усе безкоштовно до ${FREE_UNTIL.uk}. Як працюватиме оплата, ми повідомимо до першого списання.`,
    mailNote: 'Напишіть нам, щоб дізнатися про відкриття продажу:',
  },
  pledges: [
    {
      title: 'Основні функції завжди безкоштовні',
      desc:
        'Листування з шифруванням, мапа, навігація, тривоги, збережене й голос входять у вільний рівень назавжди без ліцензії.',
    },
    {
      title: 'Безстрокова ліцензія',
      desc:
        'Куплена версія лишається вашою. Роки оновлень стосуються нових збірок, а не права запускати вже оплачену версію.',
    },
    {
      title: 'Без інтернету рівень не зникає',
      desc:
        `Ліцензія перевіряється на пристрої. Платний рівень тримається ${GRACE_DAYS} днів від останньої перевірки, а потім повертається до вільного рівня без блокування.`,
    },
  ],
  plansEyebrow: 'Рівні',
  plansTitle: 'Чотири рівні, спільна основа.',
  plans: {
    free: {
      name: 'Вільний',
      who: 'Основні функції без ліцензії та без строку дії.',
      cta: 'Завантажити',
    },
    personal: {
      name: 'Особистий',
      who: 'Телефон і компʼютер у парі як одна система.',
      cta: 'Написати нам',
    },
    crew: {
      name: 'Екіпаж',
      who: 'Можливості рівня «Особистий» на більше пристроїв і довший строк.',
      cta: 'Написати нам',
    },
    unit: {
      name: 'Підрозділ',
      who: 'Для організацій: кількість пристроїв і строк оновлень за домовленістю.',
      cta: 'Написати нам',
    },
  },
  devices: (n) => {
    if (n === null) return 'Пристроїв — скільки треба';
    const tail = n % 10 === 1 && n % 100 !== 11 ? 'пристрій' : [2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100) ? 'пристрої' : 'пристроїв';
    return `${n} ${tail}`;
  },
  anyDevices: 'На скількох завгодно пристроях',
  phoneCounts: 'Телефон рахується так само, як компʼютер',
  updates: (years) =>
    years === null
      ? 'Оновлення — за домовленістю'
      : years === 1
        ? 'Рік оновлень'
        : `${years} роки оновлень`,
  perpetual: 'Після цього куплена версія працює далі',
  everythingFree: 'Усе з вільного рівня, а також:',
  scrollHint: 'Таблицю можна гортати вбік',
  notOnSale: 'Поки не продається',
  compareEyebrow: 'Порівняння',
  compareTitle: 'Що відкриває ліцензія',
  compareLead:
    `Перші шість рядків безкоштовні завжди. У телефоні пробний період на ${TRIAL_DAYS} днів відкриває все, після чого лишається вільний рівень. На компʼютері обмеження поки вимкнені. Функції «ще в роботі» не продаються.`,
  compareRowHead: 'Що входить',
  compareDevices: 'Пристрої',
  compareUpdates: 'Оновлення',
  cellUnlimited: 'без обмежень',
  cellByAgreement: 'за домовленістю',
  cellYears: (n) => (n === 1 ? '1 рік' : `${n} роки`),
  features: FEATURES_UK,
  soon: 'ще в роботі',
  included: 'є',
  notIncluded: 'немає',
  surfaces: { both: '', phone: 'лише в телефоні', pc: 'лише на компʼютері' },
  packsEyebrow: 'Пакети даних',
  packsTitle: 'Дорожні пакети зібрані, роздачу ще закрито.',
  packsBody:
    'Телефон рахує маршрут із дорожнього пакета країни на пристрої. Пакети для кількох країн уже зібрані й виміряні, але роздачу та ціни поновлення ще не відкрито.',
  packsLink: 'Подивитися, що в пакетах',
  faqTitle: 'Питання перед покупкою',
  faq: [
    {
      q: 'Коли відкриється продаж?',
      a: 'Коли зареєструємо субʼєкт господарювання і зможемо законно приймати оплату. Дати не обіцяємо. Напишіть на адресу вище — повідомимо.',
    },
    {
      q: 'Що буде після завершення років оновлень?',
      a: 'Куплена версія працює далі з усіма можливостями вашого рівня. Новіші збірки поза вікном оновлень запускаються на вільному рівні до поновлення.',
    },
    {
      q: 'Як працює пільговий строк перевірки?',
      a: `Ліцензія перевіряється на пристрої. Кожна перевірка продовжує ${GRACE_DAYS}-денний запас. Коли він вичерпується, застосунок переходить на вільний рівень: листування, мапа, навігація, тривоги, збережене й голос працюють далі.`,
    },
    {
      q: 'Скільки пристроїв на одну ліцензію?',
      a: '«Особистий» — два, «Екіпаж» — пʼять, «Підрозділ» — за домовленістю. Телефон і компʼютер рахуються однаково. Звільнити місце можна будь-коли в кабінеті.',
    },
    {
      q: 'Які мережеві запити робить застосунок?',
      a: 'Ми не збираємо статистику й не читаємо листів. До нас ідуть перевірка ліцензії, оновлення й зашифровані листи в черзі. Мапа, пошук, погода, тривоги й голосові моделі звертаються до сторонніх сервісів; Gemini працює з вашим ключем. Перелік — на /security.',
    },
    {
      q: 'Чи можливе повернення коштів?',
      a: 'Так, протягом 14 днів від покупки. Ліцензія відкликається одразу, а пристрій під час наступної перевірки переходить на вільний рівень.',
    },
    {
      q: 'Що входить у вільний рівень?',
      a: 'Листування з шифруванням, мапа, навігація, тривоги, збережене й голос. Вони працюють без ключа ліцензії та без обмеження в часі.',
    },
  ],
  portalLink: 'Маєте ліцензію? Пристрої та завантаження — у кабінеті',
};

const en: PricingCopy = {
  meta: {
    title: 'Pricing — Nightpost',
    description:
      'Messages, map, navigation, alerts, saved items and voice stay free forever. Paid tiers are a perpetual licence with years of updates. Sales have not opened yet.',
  },
  eyebrow: 'Pricing',
  title: `Free to use. Builder plans from ${euro(PRICES.studio)} a month.`,
  lead:
    'The free tier has no expiry and needs no licence. Paid tiers add phone-to-computer pairing, 3D maps and reports. Your purchased version keeps working after the update window ends.',
  saleClosed: {
    title: 'Nothing is charged yet',
    body: `Everything is free until ${FREE_UNTIL.en}. How billing will work will be announced before any charge.`,
    mailNote: 'Write to us to hear when sales open:',
  },
  pledges: [
    {
      title: 'Core features stay free',
      desc:
        'Encrypted messages, map, navigation, alerts, saved items and voice stay in the free tier forever without a licence.',
    },
    {
      title: 'Perpetual licence',
      desc:
        'The version you bought stays yours. Update years cover newer builds, not your right to run the version you paid for.',
    },
    {
      title: 'No internet, no lost tier',
      desc:
        `The licence is checked on the device. The paid tier holds for ${GRACE_DAYS} days from the last check, then returns to the free tier without locking anything.`,
    },
  ],
  plansEyebrow: 'Tiers',
  plansTitle: 'Four tiers, one free core.',
  plans: {
    free: {
      name: 'Free',
      who: 'Core features with no licence and no expiry.',
      cta: 'Download',
    },
    personal: {
      name: 'Personal',
      who: 'Phone and computer paired as one system.',
      cta: 'Write to us',
    },
    crew: {
      name: 'Crew',
      who: 'Personal tier features on more devices with longer updates.',
      cta: 'Write to us',
    },
    unit: {
      name: 'Unit',
      who: 'For organisations: device count and update window by agreement.',
      cta: 'Write to us',
    },
  },
  devices: (n) => (n === null ? 'As many devices as needed' : n === 1 ? '1 device' : `${n} devices`),
  anyDevices: 'On as many devices as you like',
  phoneCounts: 'A phone counts the same as a computer',
  updates: (years) =>
    years === null ? 'Updates by agreement' : years === 1 ? '1 year of updates' : `${years} years of updates`,
  perpetual: 'After that, the version you bought keeps working',
  everythingFree: 'Everything in Free, plus:',
  scrollHint: 'Swipe the table sideways',
  notOnSale: 'Not on sale yet',
  compareEyebrow: 'Comparison',
  compareTitle: 'What a licence opens',
  compareLead:
    `The first six rows are free forever. On the phone, a ${TRIAL_DAYS}-day trial opens everything before returning to the free tier. Computer limits are currently off. Features marked “in progress” are not sold yet.`,
  compareRowHead: 'What is included',
  compareDevices: 'Devices',
  compareUpdates: 'Updates',
  cellUnlimited: 'no limit',
  cellByAgreement: 'by agreement',
  cellYears: (n) => (n === 1 ? '1 year' : `${n} years`),
  features: FEATURES_EN,
  soon: 'in progress',
  included: 'included',
  notIncluded: 'not included',
  surfaces: { both: '', phone: 'phone only', pc: 'computer only' },
  packsEyebrow: 'Data packs',
  packsTitle: 'Road packs are built, distribution is closed.',
  packsBody:
    'The phone calculates routes from country road packs stored on the device. Packs for several countries are built and measured, but distribution and renewal pricing are not open yet.',
  packsLink: 'See what is in the packs',
  faqTitle: 'Questions before buying',
  faq: [
    {
      q: 'When will sales open?',
      a: 'Once a trading entity is registered and can legally take payment. No date is promised. Write to the address above to be notified.',
    },
    {
      q: 'What happens when update years end?',
      a: 'Your purchased version keeps working with all its tier features. Newer builds released after your window run on the free tier until updates are renewed.',
    },
    {
      q: 'How does the grace period work?',
      a: `The licence is checked on the device. Each check renews a ${GRACE_DAYS}-day allowance. When it expires, the app returns to the free tier without locking messages, map, navigation, alerts, saved items or voice.`,
    },
    {
      q: 'How many devices per licence?',
      a: 'Two on Personal, five on Crew, by agreement on Unit. Phones and computers count equally. You can release a seat in your account at any time.',
    },
    {
      q: 'What network requests does the app make?',
      a: 'We collect no usage statistics and cannot read messages. Licence checks, updates and queued encrypted messages go to our server. Map, search, weather, alerts and voice models use external services; Gemini uses your key. Full list: /security.',
    },
    {
      q: 'Are refunds available?',
      a: 'Yes, within 14 days of purchase. The licence is revoked immediately, and the device steps down to the free tier at its next check.',
    },
    {
      q: 'What is included in the free tier?',
      a: 'Encrypted messages, map, navigation, alerts, saved items and voice. They require no licence key and have no time limit.',
    },
  ],
  portalLink: 'Have a licence? Manage devices and downloads in your account',
};

export const pricingCopy: Record<Lang, PricingCopy> = { en, uk };
