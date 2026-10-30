import type { Lang, PageMeta } from './types';
import { FREE_UNTIL, PRICES, euro } from '../lib/pricing.ts';

export interface JobItem {
  id: string;
  n: string;
  line: string;
  shot: string | null;
  words?: readonly string[];
}

export interface SceneStep {
  t: string;
  shot: string;
  h: string;
  p: string;
}

export interface HomeD1Copy {
  meta: PageMeta;
  h1: string;
  sub: string;
  cta: string;
  more: string;
  facts: readonly string[];
  breadthEyebrow: string;
  breadthTitle: string;
  jobs: readonly JobItem[];
  sceneEyebrow: string;
  sceneTitle: string;
  scene: readonly SceneStep[];
  priceTitle: string;
  priceLine: string;
  priceCta: string;
  endTitle: string;
  endCta: string;
}

const en: HomeD1Copy = {
  meta: {
    title: 'Nightpost — Everything your phone is for, in one app.',
    description: 'Talk, people, the map, your day, money and places. Joined, with an assistant across all of it.',
  },
  h1: 'Everything your phone is for, in one app.',
  sub: 'Talk, people, the map, your day, money and places. Joined, with an assistant across all of it.',
  cta: 'Download for Android',
  more: 'See the product',
  facts: ['Free', 'Sign in to download'],
  breadthEyebrow: 'Six jobs, one app',
  breadthTitle: 'The things you do every day, in one place.',
  jobs: [
    { id: 'talk', n: 'Talk and share', line: 'Chats, calls, groups and channels. A message can become a task.', shot: 'talk-task' },
    { id: 'people', n: 'People in circles', line: 'Personal, close, friends, social, work. Each circle sees what you choose.', shot: null, words: ['Personal', 'Close', 'Friends', 'Social', 'Work'] },
    { id: 'map', n: 'The map', line: 'Places, and the people who share where they are with you.', shot: null, words: ['Places', 'People nearby', 'Routes'] },
    { id: 'day', n: 'Your day', line: 'Plans, shifts and what comes next, in one list.', shot: 'place-shifts' },
    { id: 'money', n: 'Money, points and gifts', line: 'Pay and get paid inside the chat. On Solana, test network today.', shot: 'money-order' },
    { id: 'places', n: 'Places and organisers', line: 'Pages, orders, bookings and shifts for people and places.', shot: 'page-shop' },
  ],
  sceneEyebrow: 'One day',
  sceneTitle: 'One day, one app.',
  scene: [
    { t: '06:52', shot: 'place-shifts', h: 'Clock in, or swap a shift.', p: 'The week, the door code and who covers Sunday.' },
    { t: '08:47', shot: 'money-order', h: 'An order arrives as a chat.', p: 'Two flat whites, paid at pickup, code 4817.' },
    { t: '12:30', shot: 'page-shop', h: 'A studio’s page.', p: 'A shop, workshops and pickup, from one page.' },
    { t: '18:44', shot: 'talk-task', h: 'A message becomes a task.', p: 'Owner, due date and checklist stay with the message.' },
  ],
  priceTitle: 'The app is free.',
  priceLine: `Builder from ${euro(PRICES.free)}. ${PRICES.free}% on sales. Free until ${FREE_UNTIL.en}.`,
  priceCta: 'See pricing',
  endTitle: 'Get Nightpost.',
  endCta: 'Download for Android',
};

const uk: HomeD1Copy = {
  meta: {
    title: 'Nightpost — усе, для чого телефон, в одному застосунку.',
    description: 'Розмови, люди, мапа, ваш день, гроші й місця. Разом, і помічник поруч у всьому.',
  },
  h1: 'Усе, для чого телефон, в одному застосунку.',
  sub: 'Розмови, люди, мапа, ваш день, гроші й місця. Разом, і помічник поруч у всьому.',
  cta: 'Завантажити для Android',
  more: 'Подивитися продукт',
  facts: ['Безкоштовно', 'Завантаження після входу'],
  breadthEyebrow: 'Шість справ, один застосунок',
  breadthTitle: 'Те, що ви робите щодня, в одному місці.',
  jobs: [
    { id: 'talk', n: 'Розмови', line: 'Чати, дзвінки, групи й канали. Повідомлення може стати завданням.', shot: 'talk-task' },
    { id: 'people', n: 'Люди в колах', line: 'Особисте, близькі, друзі, знайомі, робота. Кожне коло бачить те, що ви обрали.', shot: null, words: ['Особисте', 'Близькі', 'Друзі', 'Знайомі', 'Робота'] },
    { id: 'map', n: 'Мапа', line: 'Місця й люди, які діляться з вами, де вони.', shot: null, words: ['Місця', 'Люди поруч', 'Маршрути'] },
    { id: 'day', n: 'Ваш день', line: 'Плани, зміни й що далі — одним списком.', shot: 'place-shifts' },
    { id: 'money', n: 'Гроші, бали й подарунки', line: 'Платіть і отримуйте оплату просто в чаті. На Solana, поки в тестовій мережі.', shot: 'money-order' },
    { id: 'places', n: 'Місця й організатори', line: 'Сторінки, замовлення, бронювання й зміни для людей і місць.', shot: 'page-shop' },
  ],
  sceneEyebrow: 'Один день',
  sceneTitle: 'Один день, один застосунок.',
  scene: [
    { t: '06:52', shot: 'place-shifts', h: 'Почати зміну або помінятися.', p: 'Тиждень, код від дверей і хто підміняє в неділю.' },
    { t: '08:47', shot: 'money-order', h: 'Замовлення приходить як чат.', p: 'Два флет-вайти, оплата при отриманні, код 4817.' },
    { t: '12:30', shot: 'page-shop', h: 'Сторінка студії.', p: 'Крамниця, майстер-класи й самовивіз з однієї сторінки.' },
    { t: '18:44', shot: 'talk-task', h: 'Повідомлення стає завданням.', p: 'Відповідальний, строк і чекліст лишаються з повідомленням.' },
  ],
  priceTitle: 'Застосунок безкоштовний.',
  priceLine: `Білдер від ${euro(PRICES.free)}. ${PRICES.free}% з продажів. Безкоштовно до ${FREE_UNTIL.uk}.`,
  priceCta: 'Дивитися ціни',
  endTitle: 'Візьміть Nightpost.',
  endCta: 'Завантажити для Android',
};

export const homeD1Copy: Record<Lang, HomeD1Copy> = { en, uk };
