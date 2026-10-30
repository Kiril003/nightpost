import type { BillToken } from '../types/meetup';
import type { VenuePage } from './meetupApi';
import type { ProfileKind } from '../stores/profilesStore';

/** Профілі, у яких є сторінка, послуги й команда: Автор, Заклад, Організація. */
export const isBusinessKind = (kind: ProfileKind): boolean =>
  kind === 'business' || kind === 'author' || kind === 'place' || kind === 'org';

export interface BusinessService {
  id: string;
  name: string;
  price: string;
  token: BillToken;
}

export interface BusinessFace {
  name: string;
  address: string | null;
  services: BusinessService[];
  /** Сьогоднішні години зі сторінки; null — власник годин не дав або сьогодні не працює. */
  today: { open: string; close: string } | null;
  openNow: boolean;
  wallet: string | null;
  rating: VenuePage['rating'];
}

const DAYS = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'] as const;

const minutes = (hhmm: string): number | null => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm);
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};

/** Усе, що бізнес-профіль показує, — лише зі сторінки закладу; чого там нема, того нема й тут. */
export function businessFaceOf(page: VenuePage, now: Date = new Date()): BusinessFace {
  const services: BusinessService[] = [];
  let walletBlock: string | null = null;
  let today: BusinessFace['today'] = null;
  const day = DAYS[now.getDay()];
  for (const b of page.blocks) {
    if (b.type === 'menu') for (const it of b.items) services.push({ id: it.id, name: it.name, price: it.price, token: b.token });
    else if (b.type === 'wallet') walletBlock = b.address;
    else if (b.type === 'hours' && !today) {
      const row = b.rows.find((r) => r.day === day);
      if (row) today = { open: row.open, close: row.close };
    }
  }
  let openNow = false;
  if (today) {
    const from = minutes(today.open);
    const to = minutes(today.close);
    const at = now.getHours() * 60 + now.getMinutes();
    if (from !== null && to !== null) openNow = to > from ? at >= from && at < to : at >= from || at < to;
  }
  return {
    name: page.name,
    address: page.address,
    services,
    today,
    openNow,
    wallet: page.wallet ?? walletBlock,
    rating: page.rating,
  };
}

/** Адреса гаманця коротко: перші й останні чотири знаки. */
export const shortWallet = (address: string): string =>
  address.length > 12 ? `${address.slice(0, 4)}…${address.slice(-4)}` : address;
