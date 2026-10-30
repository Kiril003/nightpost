export const uk = {
  'joinTag.joined': '{name} у групі',
  'joinTag.close': 'у вашому Близькому',
  'joinTag.verified': 'перевірено',
  'joinTag.newKey': 'новий ключ',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'joinTag.joined': '{name} joined',
  'joinTag.close': 'in your Close',
  'joinTag.verified': 'verified',
  'joinTag.newKey': 'new key',
};
