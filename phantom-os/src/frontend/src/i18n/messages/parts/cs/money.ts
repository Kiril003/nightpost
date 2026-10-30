import type { uk } from '../money';

export const cs: Record<keyof typeof uk, string> = {
  'settle.title': 'Vyrovnat',
  'settle.pay': 'Zaplatit {amount}',
  'settle.settled': 'Vše vyrovnáno',
  'settle.pending': 'Čeká',
  'settle.failed': 'Nepodařilo se',
  'money.title': 'Peníze',
  'money.request': 'Požádat',
  'money.requestAmount': 'Požádat o {amount}',
  'money.note': 'Poznámka',
  'money.next': 'Další',
  'money.overTotal': 'Víc než celkem',
  'money.noMembers': 'Žádní členové',
  'money.cantPay': 'Nelze zaplatit',
  'money.total': 'Celkem',
};
