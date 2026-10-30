import type { uk } from '../pageReq';

export const cs: Record<keyof typeof uk, string> = {
  'pageReq.title': 'Požadavky ze stránek',
  'pageReq.book': 'Rezervace',
  'pageReq.message': 'Zpráva',
  'pageReq.noTime': 'Čas neuveden',
  'pageReq.newCount': 'Nové: {n}',
  'pageReq.order': 'Objednávka',
  'pageReq.pickup': 'Osobní odběr',
  'pageReq.delivery': 'Doručení: {zone} · {fee}',
  'pageReq.total': 'Celkem: {amount}',
  'pageReq.master': 'U koho: {name}',
  'pageReq.deposit': 'Záloha: {amount}',
  'pageReq.enroll': 'Přihláška na kurz',
  'pageReq.sessions': 'Lekce: {n}',
  'pageReq.next': 'Další: {when}',
  'pageReq.price': 'Cena: {amount}',
};
