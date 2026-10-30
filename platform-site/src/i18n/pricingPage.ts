import type { Lang, PageMeta } from './types';
import { EXTRA_PERSON, FREE_UNTIL, PRICES, euro } from '../lib/pricing.ts';

export type TierId = 'person' | 'solo' | 'place';

export interface TierCopy {
  name: string;
  price: string;
  per: string;
  seats: string;
  items: string[];
  soon: string[];
}

export interface PricingPageCopy {
  meta: PageMeta;
  eyebrow: string;
  title: string;
  lead: string;
  coreEyebrow: string;
  coreTitle: string;
  corePrice: string;
  coreItems: string[];
  coreCta: string;
  builderEyebrow: string;
  builderTitle: string;
  builderFacts: string[];
  tiers: Record<TierId, TierCopy>;
  everythingIn: (name: string) => string;
  freeUntil: string;
  builderCta: string;
  inProgress: string;
  pcEyebrow: string;
  pcTitle: string;
  pcSub: string;
}

export const TIER_ORDER: TierId[] = ['person', 'solo', 'place'];

export const pricingPageCopy: Record<Lang, PricingPageCopy> = {
  en: {
    meta: {
      title: 'Pricing — Nightpost',
      description: `The app is free. Builder for people and places: ${euro(PRICES.free)}, ${euro(PRICES.studio)} or ${euro(PRICES.place)} a month, 0% on sales, free until ${FREE_UNTIL.en}.`,
    },
    eyebrow: 'Pricing',
    title: 'The app is free.',
    lead: `Builder for people and places: ${euro(PRICES.free)}, ${euro(PRICES.studio)} or ${euro(PRICES.place)} a month, 0% on sales, free until ${FREE_UNTIL.en}.`,
    coreEyebrow: 'Nightpost',
    coreTitle: 'Everything your phone is for',
    corePrice: euro(PRICES.free),
    coreItems: ['Chats and calls', 'People', 'Map', 'Your day', 'Wallet on Solana'],
    coreCta: 'Sign in to download',
    builderEyebrow: 'Builder',
    builderTitle: 'Pages for people and places',
    builderFacts: ['0% on sales', 'Paid straight to your wallet', 'Free until 31 Dec 2026'],
    tiers: {
      person: {
        name: 'Person',
        price: euro(PRICES.free),
        per: '',
        seats: '1 person',
        items: ['Your page', 'Events', 'Pay by QR', 'Basic numbers'],
        soon: ['Services and booking', 'Drops', 'Stamp card'],
      },
      solo: {
        name: 'Solo',
        price: euro(PRICES.studio),
        per: '/ month',
        seats: '1 person',
        items: [],
        soon: ['All numbers', 'Export', 'Plan after a session', 'Reminders'],
      },
      place: {
        name: 'Studio / Place',
        price: euro(PRICES.place),
        per: '/ month',
        seats: `Up to 5 people, ${euro(EXTRA_PERSON)} per extra person`,
        items: ['Claim your place'],
        soon: ['Roles and shifts', 'Split at the till', 'Members', 'Gift cards'],
      },
    },
    everythingIn: (name) => `Everything in ${name}`,
    freeUntil: 'Free until 31 Dec 2026',
    builderCta: 'Open Builder',
    inProgress: 'in progress',
    pcEyebrow: 'Computer',
    pcTitle: 'Nightpost for computer',
    pcSub: 'Phone and computer as one system',
  },
  uk: {
    meta: {
      title: 'Ціни — Nightpost',
      description: `Застосунок безкоштовний. Builder для людей і місць: ${euro(PRICES.free)}, ${euro(PRICES.studio)} або ${euro(PRICES.place)} на місяць, 0% з продажів, безкоштовно до ${FREE_UNTIL.uk}.`,
    },
    eyebrow: 'Ціни',
    title: 'Застосунок безкоштовний.',
    lead: `Builder для людей і місць: ${euro(PRICES.free)}, ${euro(PRICES.studio)} або ${euro(PRICES.place)} на місяць, 0% з продажів, безкоштовно до ${FREE_UNTIL.uk}.`,
    coreEyebrow: 'Nightpost',
    coreTitle: 'Усе, для чого телефон',
    corePrice: euro(PRICES.free),
    coreItems: ['Розмови й дзвінки', 'Люди', 'Мапа', 'Ваш день', 'Гаманець на Solana'],
    coreCta: 'Увійти й завантажити',
    builderEyebrow: 'Builder',
    builderTitle: 'Сторінки для людей і місць',
    builderFacts: ['0% з продажів', 'Оплата одразу на ваш гаманець', 'Безкоштовно до 31.12.2026'],
    tiers: {
      person: {
        name: 'Особа',
        price: euro(PRICES.free),
        per: '',
        seats: '1 людина',
        items: ['Ваша сторінка', 'Події', 'Оплата за QR', 'Базові цифри'],
        soon: ['Послуги й запис', 'Дропи', 'Картка штампів'],
      },
      solo: {
        name: 'Соло',
        price: euro(PRICES.studio),
        per: '/ міс',
        seats: '1 людина',
        items: [],
        soon: ['Усі цифри', 'Експорт', 'План після сесії', 'Нагадування'],
      },
      place: {
        name: 'Студія / Місце',
        price: euro(PRICES.place),
        per: '/ міс',
        seats: `До 5 людей, ${euro(EXTRA_PERSON)} за кожну наступну`,
        items: ['Ваше місце на мапі'],
        soon: ['Ролі й зміни', 'Поділ рахунку на касі', 'Учасники', 'Подарункові картки'],
      },
    },
    everythingIn: (name) => `Усе з рівня «${name}»`,
    freeUntil: 'Безкоштовно до 31.12.2026',
    builderCta: 'Відкрити Builder',
    inProgress: 'у роботі',
    pcEyebrow: 'Компʼютер',
    pcTitle: 'Nightpost для компʼютера',
    pcSub: 'Телефон і компʼютер як одна система',
  },
};
