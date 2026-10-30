import type { Lang } from './types';

export interface BuilderACopy {
  places: {
    title: string;
    newPlace: string;
    create: string;
    cancel: string;
    empty: string;
    ratings: (n: number) => string;
    ratingsLabel: string;
    events: (n: number) => string;
    last28Days: (name: string) => string;
    visitors: string;
    repeat: string;
    rating: string;
    going: string;
    noVisits: string;
    nextEvents: (name: string) => string;
    newEvent: string;
    noUpcoming: string;
    goingOf: (going: number, cap: number) => string;
    goingCount: (going: number) => string;
    cancelled: string;
    asInApp: (name: string) => string;
  };
  editor: {
    back: string;
    unsaved: string;
    basics: string;
    hours: string;
    daySelected: (day: string) => string;
    closed: string;
    location: string;
    events: string;
    menu: string;
    dishesCount: (n: number) => string;
    section: string;
    dish: string;
    payoutWallet: string;
    liveAsInApp: string;
    readyTitle: string;
    finishTitle: string;
    readyOf: (done: number, total: number) => string;
    readyBasics: string;
    readyPlace: string;
    readyHours: string;
    readyMenu: (n: number) => string;
    readyWallet: string;
    add: string;
  };
  event: {
    back: string;
    upcoming: string;
    cancelled: string;
    past: string;
    editInPage: string;
    cancelEvent: string;
    restoreEvent: string;
    save: string;
    starts: string;
    ends: string;
    place: string;
    capacity: string;
    noLimit: string;
    price: string;
    free: string;
    going: string;
    goingValue: (n: number) => string;
    placesLeft: (n: number) => string;
    publishFirst: string;
    eventLabel: (venue: string) => string;
  };
}

const en: BuilderACopy = {
  places: {
    title: 'Your places',
    newPlace: 'New place',
    create: 'Create',
    cancel: 'Cancel',
    empty: 'No places yet',
    ratings: (n) => `${n} ${n === 1 ? 'rating' : 'ratings'}`,
    ratingsLabel: 'Ratings',
    events: (n) => (n === 1 ? 'Event' : 'Events'),
    last28Days: (name) => `Last 28 days · ${name}`,
    visitors: 'Visitors',
    repeat: 'Repeat visitors',
    rating: 'Rating',
    going: 'Going',
    noVisits: 'No visits yet',
    nextEvents: (name) => `Next events · ${name}`,
    newEvent: 'New event',
    noUpcoming: 'No upcoming events',
    goingOf: (going, cap) => `${going} of ${cap} going`,
    goingCount: (going) => `${going} going`,
    cancelled: 'Cancelled',
    asInApp: (name) => `${name} · as in the app`,
  },
  editor: {
    back: 'Back',
    unsaved: 'Unsaved',
    basics: 'Basics',
    hours: 'Hours',
    daySelected: (day) => `${day} selected`,
    closed: 'Closed',
    location: 'Location',
    events: 'Events',
    menu: 'Menu',
    dishesCount: (n) => `${n} ${n === 1 ? 'dish' : 'dishes'}`,
    section: 'Section',
    dish: 'Dish',
    payoutWallet: 'Payout wallet',
    liveAsInApp: 'Live · as in the app',
    readyTitle: 'Ready to publish',
    finishTitle: 'Finish your page',
    readyOf: (done, total) => `${done} of ${total}`,
    readyBasics: 'Name and about',
    readyPlace: 'Address and point',
    readyHours: 'Hours',
    readyMenu: (n) => `Menu · ${n} ${n === 1 ? 'dish' : 'dishes'}`,
    readyWallet: 'Payout wallet',
    add: 'Add',
  },
  event: {
    back: 'Back',
    upcoming: 'Upcoming',
    cancelled: 'Cancelled',
    past: 'Past',
    editInPage: 'Edit in page',
    cancelEvent: 'Cancel event',
    restoreEvent: 'Restore',
    save: 'Save',
    starts: 'Starts',
    ends: 'Ends',
    place: 'Place',
    capacity: 'Capacity',
    noLimit: 'No limit',
    price: 'Price',
    free: 'Free',
    going: 'Going',
    goingValue: (n) => `${n} going`,
    placesLeft: (n) => `${n} places left`,
    publishFirst: 'Publish the place first',
    eventLabel: (venue) => `${venue} · Event`,
  },
};

function pluralUk(n: number, one: string, few: string, many: string): string {
  const d = n % 10;
  const dd = n % 100;
  if (d === 1 && dd !== 11) return one;
  if (d >= 2 && d <= 4 && (dd < 12 || dd > 14)) return few;
  return many;
}

const uk: BuilderACopy = {
  places: {
    title: 'Ваші заклади',
    newPlace: 'Новий заклад',
    create: 'Створити',
    cancel: 'Скасувати',
    empty: 'Закладів ще немає',
    ratings: (n) => `${n} ${pluralUk(n, 'оцінка', 'оцінки', 'оцінок')}`,
    ratingsLabel: 'Оцінки',
    events: (n) => pluralUk(n, 'Подія', 'Події', 'Подій'),
    last28Days: (name) => `Останні 28 днів · ${name}`,
    visitors: 'Відвідувачі',
    repeat: 'Повторні',
    rating: 'Оцінка',
    going: 'Ідуть',
    noVisits: 'Відвідувань ще немає',
    nextEvents: (name) => `Найближчі події · ${name}`,
    newEvent: 'Нова подія',
    noUpcoming: 'Найближчих подій немає',
    goingOf: (going, cap) => `${going} з ${cap} ідуть`,
    goingCount: (going) => `${going} ідуть`,
    cancelled: 'Скасовано',
    asInApp: (name) => `${name} · як у застосунку`,
  },
  editor: {
    back: 'Назад',
    unsaved: 'Не збережено',
    basics: 'Основне',
    hours: 'Години',
    daySelected: (day) => `Обрано: ${day}`,
    closed: 'Зачинено',
    location: 'Місце',
    events: 'Події',
    menu: 'Меню',
    dishesCount: (n) => `${n} ${pluralUk(n, 'страва', 'страви', 'страв')}`,
    section: 'Розділ',
    dish: 'Страва',
    payoutWallet: 'Гаманець для виплат',
    liveAsInApp: 'Наживо · як у застосунку',
    readyTitle: 'Готовність до публікації',
    finishTitle: 'Доробіть сторінку',
    readyOf: (done, total) => `${done} з ${total}`,
    readyBasics: 'Назва й опис',
    readyPlace: 'Адреса й точка',
    readyHours: 'Години',
    readyMenu: (n) => `Меню · ${n} ${pluralUk(n, 'страва', 'страви', 'страв')}`,
    readyWallet: 'Гаманець для виплат',
    add: 'Додати',
  },
  event: {
    back: 'Назад',
    upcoming: 'Майбутня',
    cancelled: 'Скасовано',
    past: 'Минула',
    editInPage: 'Редагувати на сторінці',
    cancelEvent: 'Скасувати подію',
    restoreEvent: 'Відновити',
    save: 'Зберегти',
    starts: 'Початок',
    ends: 'Кінець',
    place: 'Місце',
    capacity: 'Місткість',
    noLimit: 'Без обмежень',
    price: 'Ціна',
    free: 'Безкоштовно',
    going: 'Ідуть',
    goingValue: (n) => `${n} ідуть`,
    placesLeft: (n) => `Ще ${n} ${pluralUk(n, 'місце', 'місця', 'місць')}`,
    publishFirst: 'Спершу опублікуйте заклад',
    eventLabel: (venue) => `${venue} · Подія`,
  },
};

export const builderA: Record<Lang, BuilderACopy> = { uk, en };
