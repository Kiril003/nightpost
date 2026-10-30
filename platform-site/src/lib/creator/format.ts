export type Lang = 'en' | 'uk';

function ukPieces(n: number): string {
  const d = n % 10;
  const h = n % 100;
  if (d === 1 && h !== 11) return 'річ';
  if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return 'речі';
  return 'речей';
}

function ukGoods(n: number): string {
  const d = n % 10;
  const h = n % 100;
  if (d === 1 && h !== 11) return 'товар';
  if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return 'товари';
  return 'товарів';
}

const COPY = {
  en: {
    left: 'left',
    ofLeft: (l: number, t: number) => `${l} of ${t} left`,
    sold: (s: number, t: number) => `${s} of ${t} sold`,
    pieces: (t: number) => `${t} pieces`,
    piecesUnit: (t: number) => (t === 1 ? 'piece' : 'pieces'),
    ofTotalLeft: (t: number) => `of ${t} left`,
    follow: 'Follow',
    following: 'Following',
    noPicture: 'No picture',
    closes: 'Closes',
    soldOut: 'Sold out',
    nLeft: (n: number) => `${n} left`,
    all: 'All',
    reviews: 'Reviews',
    reviewCount: (n: number) => `${n} reviews`,
    noReviews: 'No reviews yet',
    replies: 'Replies',
    message: 'Message',
    share: 'Share',
    pay: 'Pay',
    payNote: (token: string) => `${token} · no fees on top`,
    typeAmount: 'You type the amount',
    testPayments: 'Test payments',
    upTo: (n: number) => `up to ${n} people`,
    oneToOne: 'one to one',
    seatsLeft: (l: number, t: number) => `${l} of ${t} left`,
    full: 'Full',
    request: 'Request',
    book: 'Book',
    confirmedIn: (name: string) => `Confirmed in your chat with ${name}`,
    noTimes: 'No free times yet',
    noHours: 'No set hours yet',
    askTime: 'Ask for a time',
    service: 'Service',
    master: 'With',
    anyone: 'Anyone',
    deposit: (a: string, t: string) => `deposit ${a} ${t}`,
    depositNote: 'Deposit — in chat after confirmation',
    cart: 'Cart',
    items: (n: number) => (n === 1 ? '1 item' : `${n} items`),
    checkout: 'Check out',
    addOne: 'Add',
    less: 'One less',
    howGet: 'How to get it',
    pickup: 'Pickup',
    delivery: 'Delivery',
    pickupOnly: 'Pickup only',
    zone: 'Area',
    address: 'Delivery address',
    subtotal: 'Items',
    total: 'Total',
    minFor: (a: string) => `Minimum for this area: ${a}`,
    order: 'Order',
    ordered: 'Order sent',
    orderPayNote: 'Payment — in chat after confirmation',
    enroll: 'Enrol',
    courseLength: 'Length',
    schedule: 'Schedule',
    seats: 'Seats',
    seatsFree: 'Free seats',
    seatsOf: (total: number, left: number | null) => (left === null ? `${total}` : `${left} of ${total}`),
    done: 'Done',
    nextOne: 'Next',
    lessonN: (n: number) => `Session ${n}`,
    learn: 'What you will learn',
    courseFull: 'No seats left',
    courseOver: 'The course has ended',
    starts: (when: string) => `Starts ${when}`,
    when: 'Preferred time',
    yourName: 'Your name',
    contact: 'Email or phone',
    note: 'Note',
    send: 'Send',
    sending: 'Sending',
    sent: (name: string) => `Sent to ${name}`,
    replyTo: 'The reply comes to your contact',
    sendFailed: 'Not sent. Try again.',
    cancel: 'Close',
    booked: (name: string, when: string) => `${name} booked ${when}`,
    knownProof: (_names: string[], more: number) =>
      more > 0 ? `and ${more} people you know were here` : 'were here',
    going: (n: number) => (n === 1 ? '1 going' : `${n} going`),
    beFirst: 'No one has said yes yet',
    imGoing: 'I’m going',
    waitlist: 'Waitlist when full',
    places: (n: number) => `${n} places`,
    perShare: (amount: string, token: string) => `${amount} ${token} each`,
    paidOf: (p: number, t: number) => `${p} of ${t} paid`,
    payShare: 'Pay my share',
    askToJoin: 'Ask to join',
    approve: { owner: 'The owner says yes', members: 'Members say yes', auto: 'Open to everyone' } as Record<string, string>,
    madeOn: 'Made on Nightpost',
    sample: 'Sample page',
    sampleAct: 'This is a sample page, so nothing is sent.',
    appAct: 'Finish this in the Nightpost app.',
    getApp: 'Get the app',
    notFound: 'There is no page here',
    notFoundBody: 'The link may be old, or the page is not published yet.',
    loading: 'Opening the page',
    circles: { close: 'Close people', friends: 'Friends', social: 'Social', work: 'Work', everyone: 'Everyone' } as Record<string, string>,
    now: 'now',
    hoursClosed: 'Closed',
  },
  uk: {
    left: 'лишилось',
    ofLeft: (l: number, t: number) => `${l} з ${t} лишилось`,
    sold: (s: number, t: number) => `${s} з ${t} продано`,
    pieces: (t: number) => `${t} ${ukPieces(t)}`,
    piecesUnit: (t: number) => ukPieces(t),
    ofTotalLeft: (t: number) => `з ${t} лишилось`,
    follow: 'Стежити',
    following: 'Стежу',
    noPicture: 'Без фото',
    closes: 'Закривається',
    soldOut: 'Розпродано',
    nLeft: (n: number) => `лишилось ${n}`,
    all: 'Усе',
    reviews: 'Відгуки',
    reviewCount: (n: number) => `${n} відгуків`,
    noReviews: 'Відгуків ще немає',
    replies: 'Відповідає',
    message: 'Написати',
    share: 'Поділитися',
    pay: 'Оплата',
    payNote: (token: string) => `${token} · без доплат зверху`,
    typeAmount: 'Суму вводите ви',
    testPayments: 'Тестові платежі',
    upTo: (n: number) => `до ${n} людей`,
    oneToOne: 'віч-на-віч',
    seatsLeft: (l: number, t: number) => `${l} з ${t} вільно`,
    full: 'Повно',
    request: 'Попросити',
    book: 'Записатися',
    confirmedIn: (name: string) => `Підтвердження прийде в чат · ${name}`,
    noTimes: 'Вільного часу ще немає',
    noHours: 'Годин ще не вказано',
    askTime: 'Попросити час',
    service: 'Послуга',
    master: 'Майстер',
    anyone: 'Будь-хто',
    deposit: (a: string, t: string) => `передоплата ${a} ${t}`,
    depositNote: 'Передоплата — у чаті після підтвердження',
    cart: 'Кошик',
    items: (n: number) => `${n} ${ukGoods(n)}`,
    checkout: 'Оформити',
    addOne: 'Додати',
    less: 'Менше',
    howGet: 'Як отримати',
    pickup: 'Самовивіз',
    delivery: 'Доставка',
    pickupOnly: 'Лише самовивіз',
    zone: 'Зона',
    address: 'Адреса доставки',
    subtotal: 'Товари',
    total: 'Разом',
    minFor: (a: string) => `Мінімум для цієї зони: ${a}`,
    order: 'Замовити',
    ordered: 'Замовлення надіслано',
    orderPayNote: 'Оплата — у чаті після підтвердження',
    enroll: 'Записатися',
    courseLength: 'Тривалість',
    schedule: 'Розклад',
    seats: 'Місця',
    seatsFree: 'Вільні місця',
    seatsOf: (total: number, left: number | null) => (left === null ? `${total}` : `${left} з ${total}`),
    done: 'Завершено',
    nextOne: 'Наступне',
    lessonN: (n: number) => `Заняття ${n}`,
    learn: 'Що буде',
    courseFull: 'Місць немає',
    courseOver: 'Курс завершено',
    starts: (when: string) => `Початок ${when}`,
    when: 'Бажаний час',
    yourName: "Ваше ім'я",
    contact: 'Email або телефон',
    note: 'Примітка',
    send: 'Надіслати',
    sending: 'Надсилаємо',
    sent: (name: string) => `Надіслано: ${name}`,
    replyTo: 'Відповідь прийде на ваш контакт',
    sendFailed: 'Не надіслано. Спробуйте ще раз.',
    cancel: 'Закрити',
    booked: (name: string, when: string) => `${name}: запис на ${when}`,
    knownProof: (_names: string[], more: number) =>
      more > 0 ? `і ще ${more} знайомих тут були` : 'тут були',
    going: (n: number) => (n % 10 === 1 && n % 100 !== 11 ? `${n} йде` : `${n} йдуть`),
    beFirst: 'Ще ніхто не погодився',
    imGoing: 'Іду',
    waitlist: 'Черга, коли місць немає',
    places: (n: number) => `${n} місць`,
    perShare: (amount: string, token: string) => `${amount} ${token} з людини`,
    paidOf: (p: number, t: number) => `${p} з ${t} оплатили`,
    payShare: 'Оплатити свою частку',
    askToJoin: 'Попроситися',
    approve: { owner: 'Погоджує власник', members: 'Погоджують учасники', auto: 'Відкрито для всіх' } as Record<string, string>,
    madeOn: 'Зроблено в Nightpost',
    sample: 'Зразок сторінки',
    sampleAct: 'Це зразок сторінки, тож нічого не надсилається.',
    appAct: 'Завершіть це в застосунку Nightpost.',
    getApp: 'Завантажити',
    notFound: 'Тут немає сторінки',
    notFoundBody: 'Посилання застаріло, або сторінку ще не опубліковано.',
    loading: 'Відкриваємо сторінку',
    circles: { close: 'Близькі', friends: 'Друзі', social: 'Знайомі', work: 'Робота', everyone: 'Усі' } as Record<string, string>,
    now: 'зараз',
    hoursClosed: 'Зачинено',
  },
};

export type CreatorCopy = (typeof COPY)['en'];
export function creatorCopy(lang: Lang): CreatorCopy {
  return COPY[lang] as CreatorCopy;
}

export type Stamp = { y: number; m: number; d: number; hh: number; mm: number };

export function stamp(iso: string): Stamp | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(iso ?? '');
  if (!m) return null;
  return { y: +m[1], m: +m[2], d: +m[3], hh: m[4] ? +m[4] : 0, mm: m[5] ? +m[5] : 0 };
}

export function stampMs(s: Stamp): number {
  return Date.UTC(s.y, s.m - 1, s.d, s.hh, s.mm);
}

const locale = (lang: Lang) => (lang === 'uk' ? 'uk-UA' : 'en-GB');

export function nowStamp(d = new Date()): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function focusPosition(focus?: { x: number; y: number }): string | undefined {
  if (!focus) return undefined;
  const pc = (v: number) => `${Math.round(Math.min(1, Math.max(0, v)) * 1000) / 10}%`;
  return `${pc(focus.x)} ${pc(focus.y)}`;
}

/** A token amount as the visitor's locale writes it: «3,6» in Ukrainian, «3.6» in English. */
export function money(lang: Lang, amount: string): string {
  return lang === 'uk' ? amount.replace('.', ',') : amount;
}

export function weekday(lang: Lang, iso: string): string {
  const s = stamp(iso);
  if (!s) return '';
  return new Intl.DateTimeFormat(locale(lang), { weekday: 'short', timeZone: 'UTC' }).format(new Date(stampMs(s)));
}

export function dayNum(iso: string): number {
  return stamp(iso)?.d ?? 0;
}

export function clock(iso: string): string {
  const s = stamp(iso);
  return s ? `${String(s.hh).padStart(2, '0')}:${String(s.mm).padStart(2, '0')}` : '';
}

export function shortDate(lang: Lang, iso: string): string {
  const s = stamp(iso);
  if (!s) return '';
  return new Intl.DateTimeFormat(locale(lang), { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(
    new Date(stampMs(s)),
  );
}

export function dayMonth(lang: Lang, iso: string): string {
  const s = stamp(iso);
  if (!s) return '';
  return new Intl.DateTimeFormat(locale(lang), { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(new Date(stampMs(s)));
}

export function when(lang: Lang, iso: string): string {
  const t = clock(iso);
  return `${shortDate(lang, iso)}${t && iso.includes('T') ? ` · ${t}` : ''}`;
}

export function duration(lang: Lang, minutes: number): string {
  if (!minutes) return '';
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  const hu = lang === 'uk' ? 'год' : 'h';
  const mu = lang === 'uk' ? 'хв' : 'min';
  if (h && m) return `${h} ${hu} ${m} ${mu}`;
  return h ? `${h} ${hu}` : `${m} ${mu}`;
}

export function divide(total: string, shares: number): string {
  const [i, f = ''] = String(total).split('.');
  const scale = 100;
  const cents = Number(i) * scale + Number((f + '00').slice(0, 2));
  const each = Math.ceil(cents / Math.max(1, shares));
  const whole = Math.floor(each / scale);
  const rest = each % scale;
  return rest ? `${whole}.${String(rest).padStart(2, '0')}` : String(whole);
}

export function dayRange(days: string[], fmt: (code: string) => string): string {
  const first = days[0];
  const last = days[days.length - 1];
  return first === last ? fmt(first) : `${fmt(first)}–${fmt(last)}`;
}
