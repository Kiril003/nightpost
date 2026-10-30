import type { Lang, PageMeta } from './types';
import type { Day } from '../lib/venueBlocks';
import type { DraftProblem } from '../components/builder/draft';

/**
 * «Білдер» — сторінка закладу, яку власник складає на сайті (/build).
 *
 * Закон тексту той самий, що для решти сайту: заголовок — висновок, блок до
 * сорока слів, жодної обіцянки, якої код не тримає. Заклад бачать у чаті
 * застосунку лише після того, як туди доїде збірка 18 — тому тут немає
 * «вже бачать тисячі», а є «з наступною збіркою люди відкриватимуть».
 */
export interface BuildCopy {
  meta: PageMeta;
  hero: { eyebrow: string; title: string; sub: string; devnet: string };
  start: { lead: string; link: string; openPage: string };
  signedOut: { title: string; body: string; cta: string; kept: string };
  unavailable: { title: string; body: string };
  loading: string;
  retry: string;
  list: {
    title: string;
    empty: string;
    newName: string;
    newNamePlaceholder: string;
    create: string;
    published: string;
    draft: string;
    open: string;
  };
  editor: {
    back: string;
    title: string;
    titleHint: string;
    about: string;
    aboutHint: string;
    address: string;
    addressPlaceholder: string;
    point: string;
    pointPlaceholder: string;
    pointHint: string;
    pointBad: string;
    pointMissing: string;
    addressMissing: string;
    hours: string;
    hoursHint: string;
    addHours: string;
    open: string;
    close: string;
    events: string;
    addEvent: string;
    eventTitle: string;
    eventStarts: string;
    eventEnds: string;
    remove: string;
    menus: string;
    menusHint: string;
    menuTitle: string;
    menuTitlePlaceholder: string;
    token: string;
    tokenHint: string;
    dish: string;
    dishPlaceholder: string;
    price: string;
    priceBad: (token: string) => string;
    note: string;
    addDish: string;
    addMenu: string;
    removeMenu: string;
    wallet: string;
    walletHint: string;
    walletPlaceholder: string;
  };
  actions: {
    save: string;
    saveChanges: string;
    publish: string;
    unpublish: string;
    saved: string;
    publishedNow: string;
    unpublishedNow: string;
    unsaved: string;
    restored: string;
    remove: string;
    removeSure: string;
    rev: (n: number) => string;
  };
  preview: {
    title: string;
    lead: string;
    events: string;
    rating: (mean: string, count: number) => string;
    noRating: string;
    noMenu: string;
    payVenue: string;
    payOrganizer: string;
    untitled: string;
  };
  days: Record<Day, string>;
  problems: Record<DraftProblem, string> & { generic: string; network: string; forbidden: string; notFound: string };
}

const uk: BuildCopy = {
  meta: {
    title: 'Білдер — сторінка закладу — Nightpost',
    description: 'Сторінка закладу без коду: назва, адреса, години й меню з цінами в USDC або SOL. Безкоштовно.',
  },
  hero: {
    eyebrow: 'Місця',
    title: 'Ваші місця',
    sub: 'Сторінка вашого місця в застосунку: години, меню, події.',
    devnet: 'Ціни — у USDC або SOL. Оплати поки ходять лише в тестовій мережі Solana.',
  },
  start: {
    lead: 'Сторінка для себе, гурту чи місця — в одному з трьох виглядів.',
    link: 'Почати сторінку',
    openPage: 'Відкрити опубліковану сторінку',
  },
  signedOut: {
    title: 'Спершу увійдіть у кабінет',
    body: 'Сторінка закладу належить обліковому запису. Вхід — кодом на пошту, без пароля.',
    cta: 'Увійти в кабінет',
    kept: 'Сесія скінчилась, а незбережені зміни сторінки — ні: вони чекають у цій вкладці й повернуться, щойно ви ввійдете.',
  },
  unavailable: {
    title: 'Сервер ще не приймає сторінки закладів',
    body: 'Двері для закладів відчиняться з наступним оновленням сервера. Зберегти сторінку зараз нікуди — тому редактор вимкнено.',
  },
  loading: 'Завантажуємо ваші заклади…',
  retry: 'Спробувати ще раз',
  list: {
    title: 'Ваші місця',
    empty: 'Закладів ще немає. Почніть із назви.',
    newName: 'Назва закладу',
    newNamePlaceholder: 'Cafe X',
    create: 'Створити сторінку',
    published: 'Опубліковано',
    draft: 'Чернетка',
    open: 'Відкрити',
  },
  editor: {
    back: 'Усі заклади',
    title: 'Назва',
    titleHint: 'Так заклад підпишуть у чаті й на картці зустрічі.',
    about: 'Про заклад',
    aboutHint: 'Два-три речення: що тут їдять і чому сюди йдуть.',
    address: 'Адреса',
    addressPlaceholder: 'Вулиця і номер',
    point: 'Точка на мапі',
    pointPlaceholder: 'Широта, довгота',
    pointHint: 'Широта й довгота через кому. На будь-якій мапі: правий клік по входу → скопіювати координати.',
    pointBad: 'Не схоже на координати. Формат: широта, довгота — два числа через кому.',
    pointMissing: 'Додайте точку на мапі. Формат: широта, довгота — два числа через кому.',
    addressMissing: 'Додайте адресу: без неї точку не збережемо.',
    hours: 'Години',
    hoursHint: 'Закриття після півночі — це нормально: 18:00–02:00.',
    addHours: 'Додати день',
    open: 'Відчинено з',
    close: 'до',
    events: 'Події',
    addEvent: 'Додати подію',
    eventTitle: 'Подія',
    eventStarts: 'Початок',
    eventEnds: 'Кінець',
    remove: 'Прибрати',
    menus: 'Меню',
    menusHint: 'З цього меню друзі в чаті позначають свої страви, і рахунок ділиться точно.',
    menuTitle: 'Розділ',
    menuTitlePlaceholder: 'Кухня',
    token: 'Монета цін',
    tokenHint: 'Одна на все меню: рахунок за столом ведеться однією монетою.',
    dish: 'Страва',
    dishPlaceholder: 'Страва',
    price: 'Ціна',
    priceBad: (token) => `Ціна — додатне число, до ${token === 'SOL' ? '9' : '6'} знаків після коми.`,
    note: 'Примітка',
    addDish: 'Додати страву',
    addMenu: 'Додати розділ',
    removeMenu: 'Прибрати розділ',
    wallet: 'Гаманець закладу (необовʼязково)',
    walletHint: 'Якщо вказати, організатор зустрічі зможе обрати «платити закладу». Без нього кожен платить організаторові.',
    walletPlaceholder: 'Адреса Solana',
  },
  actions: {
    save: 'Зберегти чернетку',
    saveChanges: 'Зберегти зміни',
    publish: 'Опублікувати',
    unpublish: 'Зняти з публікації',
    saved: 'Збережено.',
    publishedNow: 'Опубліковано. Заклад можна знайти поруч.',
    unpublishedNow: 'Знято з публікації. Сторінку бачите лише ви.',
    unsaved: 'Є незбережені зміни',
    restored: 'Повернули незбережені зміни. Збережіть їх.',
    remove: 'Видалити чернетку',
    removeSure: 'Видалити назавжди',
    rev: (n) => `версія ${n}`,
  },
  preview: {
    title: 'Так її побачать',
    lead: 'Сторінка в чаті має той самий зміст.',
    events: 'Події',
    rating: (mean, count) => `${mean} · ${count} ${plural(count, 'оцінка', 'оцінки', 'оцінок')}`,
    noRating: 'Ще ніхто не оцінив',
    noMenu: 'Меню ще немає — рахунок за цим закладом можна буде лише ввести вручну.',
    payVenue: 'Можна платити закладу напряму',
    payOrganizer: 'Частки платять організаторові',
    untitled: 'Без назви',
  },
  days: { mon: 'Пн', tue: 'Вт', wed: 'Ср', thu: 'Чт', fri: 'Пт', sat: 'Сб', sun: 'Нд' },
  problems: {
    bad_page: 'Сторінка зламана. Оновіть вкладку.',
    bad_version: 'Сторінку складено старою версією Білдера. Оновіть вкладку.',
    too_many_blocks: 'Забагато розділів на одній сторінці.',
    unknown_block: 'Такого розділу Білдер не знає.',
    unknown_field: 'У розділі є поле, якого сервер не приймає.',
    missing_field: 'У розділі бракує обовʼязкового поля.',
    missing_title: 'Без назви сторінку не зберегти.',
    duplicate_block: 'Цей розділ може бути лише один.',
    bad_text: 'Заповніть поле або скоротіть текст.',
    bad_point: 'Не схоже на точку на мапі. Формат: широта, довгота — два числа через кому.',
    missing_point: 'Додайте точку на мапі — адресу без неї не збережемо. Формат: широта, довгота — два числа через кому.',
    missing_address: 'Точка є, а адреси немає. Додайте адресу — без неї точку не збережемо.',
    bad_hours: 'Перевірте години: формат 08:00.',
    bad_token: 'Монета цін — USDC або SOL.',
    mixed_tokens: 'Усі розділи меню мають бути в одній монеті.',
    no_items: 'У розділі меню немає жодної страви.',
    too_many_items: 'Забагато страв: до 100 у розділі й до 200 на сторінці.',
    bad_item: 'Дайте страві назву.',
    bad_item_id: 'Код страви зіпсовано. Видаліть страву й додайте знову.',
    duplicate_item: 'Дві страви з однаковою назвою — перейменуйте одну.',
    bad_price: 'Ціна — додатне число через крапку чи кому.',
    bad_wallet: 'Це не схоже на адресу Solana.',
    bad_event_time: 'Перевірте час події: кінець має бути після початку.',
    missing_event_start: 'Додайте початок події.',
    duplicate_event: 'Дві події з однаковою назвою — перейменуйте одну.',
    too_many_events: 'Забагато подій: до 20 на сторінці.',
    needs_address: 'Щоб опублікувати, потрібні адреса й точка на мапі: без них заклад не знайдеться поруч.',
    not_a_list: 'Сторінка зламана. Оновіть вкладку.',
    too_big: 'Сторінка завелика: скоротіть меню чи опис.',
    too_many_venues: 'На один обліковий запис — до десяти закладів.',
    generic: 'Сервер відмовив. Спробуйте ще раз.',
    network: 'Сервер не відповів. Перевірте звʼязок — зміни лишились на сторінці.',
    forbidden: 'Цей заклад належить іншому обліковому запису.',
    notFound: 'Такого закладу немає або його бачить лише власник.',
  },
};

const en: BuildCopy = {
  meta: {
    title: 'Builder — your venue page — Nightpost',
    description: 'A venue page without code: name, address, hours and a menu priced in USDC or SOL. Free.',
  },
  hero: {
    eyebrow: 'Places',
    title: 'Your places',
    sub: 'A page for your place in the app: hours, menu, events.',
    devnet: 'Prices are in USDC or SOL. Payments run on the Solana test network for now.',
  },
  start: {
    lead: 'A page for yourself, a group or a place, in one of three looks.',
    link: 'Start a page',
    openPage: 'Open the published page',
  },
  signedOut: {
    title: 'Sign in to your account first',
    body: 'A venue page belongs to an account. Sign-in is a code by email, no password.',
    cta: 'Sign in',
    kept: 'Your session ended, but your unsaved page changes did not: they wait in this tab and come back once you sign in.',
  },
  unavailable: {
    title: 'The server does not accept venue pages yet',
    body: 'Venue pages open with the next server update. There is nowhere to save a page today, so the editor is off.',
  },
  loading: 'Loading your venues…',
  retry: 'Try again',
  list: {
    title: 'Your places',
    empty: 'No venues yet. Start with a name.',
    newName: 'Venue name',
    newNamePlaceholder: 'Cafe X',
    create: 'Create page',
    published: 'Published',
    draft: 'Draft',
    open: 'Open',
  },
  editor: {
    back: 'All venues',
    title: 'Name',
    titleHint: 'How the venue is labelled in chat and on the meetup card.',
    about: 'About',
    aboutHint: 'Two or three sentences: what people eat here and why they come.',
    address: 'Address',
    addressPlaceholder: 'Street and number',
    point: 'Point on the map',
    pointPlaceholder: 'Latitude, longitude',
    pointHint: 'Latitude and longitude, comma-separated. On any map: right-click the entrance → copy coordinates.',
    pointBad: 'That does not look like coordinates. Format: latitude, longitude — two numbers with a comma.',
    pointMissing: 'Add a map point. Format: latitude, longitude — two numbers with a comma.',
    addressMissing: 'Add an address: a point cannot be saved without one.',
    hours: 'Hours',
    hoursHint: 'Closing after midnight is fine: 18:00–02:00.',
    addHours: 'Add a day',
    open: 'Open from',
    close: 'to',
    events: 'Events',
    addEvent: 'Add event',
    eventTitle: 'Event',
    eventStarts: 'Starts',
    eventEnds: 'Ends',
    remove: 'Remove',
    menus: 'Menu',
    menusHint: 'Friends tick their dishes from this menu in chat, and the bill splits exactly.',
    menuTitle: 'Section',
    menuTitlePlaceholder: 'Kitchen',
    token: 'Price currency',
    tokenHint: 'One for the whole menu: a table settles in one currency.',
    dish: 'Dish',
    dishPlaceholder: 'Dish',
    price: 'Price',
    priceBad: (token) => `A price is a positive number with up to ${token === 'SOL' ? '9' : '6'} decimals.`,
    note: 'Note',
    addDish: 'Add a dish',
    addMenu: 'Add a section',
    removeMenu: 'Remove section',
    wallet: 'Venue wallet (optional)',
    walletHint: 'With it, the meetup organizer can choose “pay the venue”. Without it, everyone pays the organizer.',
    walletPlaceholder: 'Solana address',
  },
  actions: {
    save: 'Save draft',
    saveChanges: 'Save changes',
    publish: 'Publish',
    unpublish: 'Unpublish',
    saved: 'Saved.',
    publishedNow: 'Published. People nearby can find the venue.',
    unpublishedNow: 'Unpublished. Only you can see the page.',
    unsaved: 'Unsaved changes',
    restored: 'We brought back your unsaved changes. Save them.',
    remove: 'Delete draft',
    removeSure: 'Delete for good',
    rev: (n) => `version ${n}`,
  },
  preview: {
    title: 'What people see',
    lead: 'The page in chat carries the same content.',
    events: 'Events',
    rating: (mean, count) => `${mean} · ${count} ${count === 1 ? 'rating' : 'ratings'}`,
    noRating: 'No ratings yet',
    noMenu: 'No menu yet — a bill here can only be typed in by hand.',
    payVenue: 'Shares can be paid to the venue directly',
    payOrganizer: 'Shares are paid to the organizer',
    untitled: 'Untitled',
  },
  days: { mon: 'Mon', tue: 'Tue', wed: 'Wed', thu: 'Thu', fri: 'Fri', sat: 'Sat', sun: 'Sun' },
  problems: {
    bad_page: 'The page is broken. Reload the tab.',
    bad_version: 'This page was made by an older Builder. Reload the tab.',
    too_many_blocks: 'Too many sections on one page.',
    unknown_block: 'Builder does not know this section.',
    unknown_field: 'A section has a field the server does not accept.',
    missing_field: 'A section is missing a required field.',
    missing_title: 'A page needs a name before it can be saved.',
    duplicate_block: 'There can be only one of this section.',
    bad_text: 'Fill in the field or shorten the text.',
    bad_point: 'That does not look like a map point. Format: latitude, longitude — two numbers with a comma.',
    missing_point: 'Add a map point — an address cannot be saved without one. Format: latitude, longitude — two numbers with a comma.',
    missing_address: 'There is a point but no address. Add an address — the point cannot be saved without one.',
    bad_hours: 'Check the hours: format 08:00.',
    bad_token: 'The price currency is USDC or SOL.',
    mixed_tokens: 'All menu sections must use one currency.',
    no_items: 'A menu section has no dishes.',
    too_many_items: 'Too many dishes: up to 100 per section and 200 per page.',
    bad_item: 'Give the dish a name.',
    bad_item_id: 'A dish code is broken. Remove the dish and add it again.',
    duplicate_item: 'Two dishes share a name — rename one.',
    bad_price: 'A price is a positive number.',
    bad_wallet: 'That does not look like a Solana address.',
    bad_event_time: 'Check the event time: end must be after start.',
    missing_event_start: 'Add when the event starts.',
    duplicate_event: 'Two events share a name — rename one.',
    too_many_events: 'Too many events: up to 20 per page.',
    needs_address: 'To publish, add an address and a map point: without them nobody nearby finds the venue.',
    not_a_list: 'The page is broken. Reload the tab.',
    too_big: 'The page is too big: shorten the menu or the description.',
    too_many_venues: 'One account can hold up to ten venues.',
    generic: 'The server refused. Try again.',
    network: 'The server did not answer. Check your connection — your changes are still on the page.',
    forbidden: 'This venue belongs to another account.',
    notFound: 'No such venue, or only its owner can see it.',
  },
};

function plural(n: number, one: string, few: string, many: string): string {
  const d = n % 10;
  const dd = n % 100;
  if (d === 1 && dd !== 11) return one;
  if (d >= 2 && d <= 4 && (dd < 12 || dd > 14)) return few;
  return many;
}

export const buildCopy: Record<Lang, BuildCopy> = { uk, en };
