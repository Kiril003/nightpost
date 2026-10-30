export const uk = {
  'payGuard.keyChanged.one': 'Ключ змінився {n} день тому',
  'payGuard.keyChanged.few': 'Ключ змінився {n} дні тому',
  'payGuard.keyChanged.many': 'Ключ змінився {n} днів тому',
  'payGuard.keyChangedToday': 'Ключ змінився сьогодні',
  'payGuard.first': 'Перший переказ',
  'payGuard.check': 'Перевірити',
  'payGuard.hold': 'Утримуйте 2 с',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'payGuard.keyChanged.one': 'Key changed {n} day ago',
  'payGuard.keyChanged.few': 'Key changed {n} days ago',
  'payGuard.keyChanged.many': 'Key changed {n} days ago',
  'payGuard.keyChangedToday': 'Key changed today',
  'payGuard.first': 'First payment',
  'payGuard.check': 'Check',
  'payGuard.hold': 'Hold 2 s',
};
