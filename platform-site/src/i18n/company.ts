import type { Item, Lang, PageMeta } from './types';

/**
 * Розділ «Компанія» та інституційна обгортка сайту: верхня службова смуга,
 * згруповане меню, підвал.
 *
 * Редакційний закон цієї сторінки: жодного факту, якого ще немає на сайті.
 * Ні імен, ні року заснування, ні розміру команди, ні логотипів клієнтів,
 * ні сертифікатів. Усе, що тут написано, або вже заявлено на /security та
 * /legal, або перевіряється в самому продукті. Порожня сторінка «Про нас»
 * коштує дешевше, ніж вигадана.
 */
export interface CompanyCopy {
  meta: PageMeta;
  hero: { eyebrow: string; title: string; sub: string };
  /** Що ми будуємо — з наявних сторінок продукту. */
  build: { eyebrow: string; title: string; lead: string; points: Item[] };
  /** Як влаштована робота: тільки перевірювані зобов'язання. */
  practice: { eyebrow: string; title: string; lead: string; points: Item[] };
  /** Правовий стан — те саме, що на /legal, але на видноті. */
  standing: {
    eyebrow: string;
    title: string;
    lead: string;
    statusLabel: string;
    pending: string;
    trading: string;
    jurisdictionLabel: string;
    revisionLabel: string;
    legalLink: string;
  };
  contact: {
    eyebrow: string;
    title: string;
    lead: string;
    channels: Record<'sales' | 'support' | 'security' | 'press', { name: string; desc: string }>;
    responseNote: string;
  };
}

export interface ShellCopy {
  /** Тема листа «живе демо». Зашита українською в company.ts, вона їхала
   *  такою і в англійську версію сайту — тобто англомовний відвідувач
   *  відкривав поштовий клієнт із темою, якої не читає. */
  demoSubject: string;
  /** Один рядок над сторінками старої картини продукту. */
  legacyNotice: { text: string; back: string };
  /** Службова смуга над меню. */
  utility: { security: string; legal: string; status: string };
  nav: {
    os: string;
    companion: string;
    /** Сторінка /how: як влаштовано продукт. */
    how: string;
    security: string;
    pricing: string;
    builder: string;
    legal: string;
    privacy: string;
    download: string;
    get: string;
    /** Одні двері до всього, що стосується покупки: ключ, пристрої, файли. */
    cabinet: string;
    company: string;
    contact: string;
  };
  footer: {
    productHeading: string;
    companyHeading: string;
    contactHeading: string;
    disclosure: string;
    disclosureLink: string;
    jurisdictionLabel: string;
    statusHeading: string;
  };
}

const ukShell: ShellCopy = {
  demoSubject: 'Nightpost — живе демо',
  /*
   * Смуга над сторінками старої картини продукту.
   *
   * Казала «описує ранню версію продукту» — і цим ховала не незавершеність, а
   * вигадку: «живе демо» на /companion і пульт на /world — це намальовані на
   * самому сайті симуляції з вигаданими даними, без жодного мережевого
   * виклику. Евфемізм робив їх схожими на застарілий, але справжній продукт.
   */
  legacyNotice: {
    text: 'Ця сторінка описує ранню картину продукту й буде переписана. Усе інтерактивне на ній намальовано на сайті, а не знято з застосунку.',
    back: 'На головну',
  },
  utility: {
    security: 'Безпека та дані',
    legal: 'Правові умови',
    status: 'Стан продукту',
  },
  nav: {
    os: 'Nightpost',
    companion: 'Nightpost',
    how: 'Як це працює',
    security: 'Безпека та дані',
    pricing: 'Ціни',
    builder: 'Білдер',
    legal: 'Правові умови',
    privacy: 'Приватність',
    download: 'Завантажити',
    get: 'Завантажити',
    cabinet: 'Я',
    company: 'Про компанію',
    contact: 'Контакти',
  },
  footer: {
    productHeading: 'Продукт',
    companyHeading: 'Компанія',
    contactHeading: 'Канали звернень',
    disclosure: 'Знайшли вразливість?',
    disclosureLink: 'Політика розкриття',
    jurisdictionLabel: 'Юрисдикція',
    statusHeading: 'Правовий стан',
  },
};

const enShell: ShellCopy = {
  demoSubject: 'Nightpost — live demo',
  legacyNotice: {
    text: 'This page describes an early picture of the product and will be rewritten. Everything interactive on it is drawn on this site, not captured from the app.',
    back: 'Go to the overview',
  },
  utility: {
    security: 'Security and data',
    legal: 'Legal terms',
    status: 'Product status',
  },
  nav: {
    os: 'Nightpost',
    companion: 'Nightpost',
    how: 'How it works',
    security: 'Security and data',
    pricing: 'Pricing',
    builder: 'Builder',
    legal: 'Legal terms',
    privacy: 'Privacy',
    download: 'Download',
    get: 'Download',
    cabinet: 'You',
    company: 'About the company',
    contact: 'Contact',
  },
  footer: {
    productHeading: 'Product',
    companyHeading: 'Company',
    contactHeading: 'Contact channels',
    disclosure: 'Found a vulnerability?',
    disclosureLink: 'Disclosure policy',
    jurisdictionLabel: 'Jurisdiction',
    statusHeading: 'Legal standing',
  },
};

const uk: CompanyCopy = {
  meta: {
    title: 'Про компанію — Nightpost',
    description:
      'Хто робить Nightpost, за якими правилами й куди писати. Правовий стан, канали звернень і зобовʼязання, які можна перевірити в самому продукті.',
  },
  hero: {
    eyebrow: 'Про компанію',
    title: 'Робимо Nightpost, щоб люди лишались на звʼязку.',
    // Колись тут стояло «назовні йдуть рівно дві речі». Відтоді застосунок
    // навчився живого зʼєднання для листів і повідомлень із мапи, і будь-яке
    // «рівно» тут застаріває першим. Тому — посилання на перепис, а не лік.
    sub:
      'Більшість того, що робить Nightpost, відбувається на вашому телефоні чи компʼютері. Те, що все ж іде на наші сервери, — перевірка ліцензії, листи для тих, хто поза мережею, повідомлення з мапи — перелічено запит за запитом на сторінці «Безпека та дані».',
  },
  build: {
    eyebrow: 'Що ми робимо',
    title: 'Застосунок для телефона і програма для компʼютера.',
    lead: 'Телефон — головне. Компʼютер додає більший екран і може обмінюватися з телефоном. Що з цього вже готове, сторінка завантажень каже прямо.',
    points: [
      {
        title: 'Nightpost для Android',
        desc: 'Листи між людьми, мапа з тривогами й укриттями, помічник. Файлом із нашого сервера, з версією й контрольною сумою. Google Play — скоро.',
      },
      {
        title: 'Nightpost для компʼютера',
        desc: 'Програма, яка вміє обмінюватися з телефоном. Готової збірки на роздачі ще немає — коли вона зʼявиться, її буде на сторінці завантажень.',
      },
      {
        title: 'Без реєстрації',
        desc: 'Люди й пристрої знаходять одне одного за кодом або запрошенням. Ні пошти, ні пароля, ні номера телефона.',
      },
    ],
  },
  practice: {
    eyebrow: 'Як ми працюємо',
    title: 'Зобовʼязання, які можна перевірити.',
    lead: 'Кожен рядок нижче або видно в самому продукті, або розписано на сторінці «Безпека та дані» — що саме йде в мережу і коли.',
    points: [
      {
        title: 'Ваші дані — у вас',
        desc: 'Розмови, контакти й збережені місця лежать на вашому пристрої. Ні аналітики, ні збирача падінь. Те, що йде на наші сервери, перелічено на сторінці безпеки запит за запитом.',
      },
      {
        title: 'На цьому сайті немає аналітики',
        desc: 'Статична збірка без жодного стороннього скрипта, піксела чи вбудованої форми. Обіцянка не стежити стосується й сторінки, яку ви зараз читаєте.',
      },
      {
        title: 'Ліцензія переживає відсутність мережі',
        desc: 'Ключ перевіряється на самому пристрої. Пропав інтернет — пільговий строк рахується від останньої вдалої перевірки, а не від вимкнення.',
      },
      {
        title: 'Кожна збірка підписана',
        desc: 'Файл для Android підписано релізним ключем, а його контрольна сума SHA-256 стоїть поруч на сторінці завантажень — звірити можна до встановлення.',
      },
      {
        title: 'Стан робіт — як є',
        desc: 'Те, що працює, і те, що ще в роботі, стоять поруч на видноті. Де дати немає, ми її не називаємо.',
      },
    ],
  },
  standing: {
    eyebrow: 'Правовий стан',
    title: 'Продаж ще не відкрито, і ми не вдаємо інакше.',
    lead:
      'Субʼєкта господарювання не зареєстровано, тож приймати оплату нікому. Щойно реквізити зʼявляться, вони стануть тут і в правових умовах — до того часу тут порожньо навмисно.',
    statusLabel: 'Стан',
    pending: 'Продаж не відкрито',
    trading: 'Продаж відкрито',
    jurisdictionLabel: 'Юрисдикція',
    revisionLabel: 'Редакція умов',
    legalLink: 'Читати правові умови',
  },
  contact: {
    eyebrow: 'Звернення',
    title: 'Чотири адреси замість однієї скриньки.',
    lead: 'Пишіть у той канал, якого стосується лист, — так відповідь прийде швидше й від того, хто справді може допомогти.',
    channels: {
      sales: { name: 'Продажі та демонстрації', desc: 'Жива демонстрація, закупівля, кілька пристроїв в одній ліцензії.' },
      support: { name: 'Підтримка', desc: 'Як працює застосунок, активація ключа, керування пристроями.' },
      security: { name: 'Безпека', desc: 'Повідомлення про вразливість. Відповідає людина, а не форма.' },
      press: { name: 'Медіа та партнерства', desc: 'Запити журналістів, спільні програми, гранти.' },
    },
    responseNote: 'Відповідає людина, не автовідповідач. Українською або англійською.',
  },
};

const en: CompanyCopy = {
  meta: {
    title: 'About the company — Nightpost',
    description:
      'Who builds Nightpost, under what rules, and where to write. Legal standing, contact channels, and commitments you can check in the product itself.',
  },
  hero: {
    eyebrow: 'About the company',
    title: 'We make Nightpost so people can stay in touch.',
    sub:
      'Most of what Nightpost does happens on your phone or computer. What does go to our servers — the licence check, letters for people who are offline, reports from the map — is listed request by request on the security page.',
  },
  build: {
    eyebrow: 'What we make',
    title: 'An app for the phone and a program for the computer.',
    lead: 'The phone comes first. The computer adds a bigger screen and can exchange with the phone. The download page says plainly which of these is ready.',
    points: [
      {
        title: 'Nightpost for Android',
        desc: 'Letters between people, a map with alerts and shelters, an assistant. A file from our server, with its version and checksum. Google Play is coming.',
      },
      {
        title: 'Nightpost for the computer',
        desc: 'A program that can exchange with your phone. There is no build to download yet — when there is, it will be on the download page.',
      },
      {
        title: 'No sign-up',
        desc: 'People and devices find each other with a code or an invitation. No e-mail, no password, no phone number.',
      },
    ],
  },
  practice: {
    eyebrow: 'How we work',
    title: 'Commitments you can check.',
    lead: 'Every line below is either visible in the product or spelled out on the security page — what goes over the network, and when.',
    points: [
      {
        title: 'Your data stays with you',
        desc: 'Conversations, contacts and saved places live on your device. No analytics, no crash reporter. What does go to our servers is listed on the security page, request by request.',
      },
      {
        title: 'No analytics on this site',
        desc: 'A static build with no third-party script, pixel or embedded form. The promise not to watch you covers the page you are reading right now.',
      },
      {
        title: 'The licence survives losing the network',
        desc: 'The key is checked on the device itself. Lose the internet and the grace period counts from the last successful check, not from the outage.',
      },
      {
        title: 'Every build is signed',
        desc: 'The Android file is signed with the release key, and its SHA-256 checksum stands next to it on the download page — you can check it before installing.',
      },
      {
        title: 'Status as it is',
        desc: 'What works and what is still in progress stand side by side in the open. Where there is no date, we do not give one.',
      },
    ],
  },
  standing: {
    eyebrow: 'Legal standing',
    title: 'Sales are not open yet, and we do not pretend otherwise.',
    lead:
      'No trading entity is registered, so there is nobody to take payment. The moment the details exist they appear here and in the legal terms — until then this space is deliberately empty.',
    statusLabel: 'Status',
    pending: 'Sales not open',
    trading: 'Sales open',
    jurisdictionLabel: 'Jurisdiction',
    revisionLabel: 'Terms revision',
    legalLink: 'Read the legal terms',
  },
  contact: {
    eyebrow: 'Contact',
    title: 'Four addresses instead of one inbox.',
    lead: 'Write to the channel your message belongs to — the answer comes back faster and from someone who can actually help.',
    channels: {
      sales: { name: 'Sales and demos', desc: 'A live demo, purchasing, several devices under one licence.' },
      support: { name: 'Support', desc: 'How the app behaves, key activation, managing devices.' },
      security: { name: 'Security', desc: 'Vulnerability reports. A person answers, not a form.' },
      press: { name: 'Media and partnerships', desc: 'Press enquiries, joint programmes, grants.' },
    },
    responseNote: 'A person answers, not an autoresponder. In Ukrainian or English.',
  },
};

export const companyCopy: Record<Lang, CompanyCopy> = { uk, en };
export const shellCopy: Record<Lang, ShellCopy> = { uk: ukShell, en: enShell };
