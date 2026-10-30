import type { uk } from '../payGuard';

export const cs: Record<keyof typeof uk, string> = {
  'payGuard.keyChanged.one': 'Klíč se změnil před {n} dnem',
  'payGuard.keyChanged.few': 'Klíč se změnil před {n} dny',
  'payGuard.keyChanged.many': 'Klíč se změnil před {n} dny',
  'payGuard.keyChangedToday': 'Klíč se změnil dnes',
  'payGuard.first': 'První platba',
  'payGuard.check': 'Zkontrolovat',
  'payGuard.hold': 'Podržte 2 s',
};
