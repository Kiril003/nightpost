import type { uk } from '../callLog';

export const cs: Record<keyof typeof uk, string> = {
  'callLog.in': 'Příchozí hovor',
  'callLog.out': 'Odchozí hovor',
  'callLog.missed': 'Zmeškaný hovor',
  'callLog.declined': 'Odmítnutý hovor',
  'callLog.video': 'video',
  'callLog.callBack': 'Zavolat zpět',
  'callLog.empty': 'Zatím žádné hovory',
  'callLog.settings': 'Nastavení hovorů',
  'callLog.recent': 'Poslední',
};
