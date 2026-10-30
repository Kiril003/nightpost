import type { uk } from '../points';

export const cs: Record<keyof typeof uk, string> = {
  'points.title': 'Body',
  'points.loading': 'Načítání bodů…',
  'points.offline': 'Server bodů není dostupný',
  'points.empty': 'Zatím žádné body',
  'points.private': 'Vidíte je jen vy',
  'points.kind.meetup': 'Setkání proběhlo',
  'points.kind.payment': 'První zaplacený podíl',
  'points.kind.invite': 'Přítel se přidal přes pozvánku',
  'points.kind.other': 'Jiná akce',
  'points.checkin.awarded': 'Setkání započítáno — body připsány.',
  'points.checkin.waiting': 'Check-in přijat: setkání se započítá, až se na místě ohlásí ještě někdo.',
  'points.checkin.counted': 'Check-in přijat. Setkání už je započítáno — nové body za něj nejsou.',
  'points.checkin.sent': 'Check-in odeslán.',
};
