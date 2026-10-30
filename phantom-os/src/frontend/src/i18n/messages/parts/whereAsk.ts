export const uk = {
  'whereAsk.exact15': 'Точно · 15 хв',
  'whereAsk.area60': 'Район · 1 год',
  'whereAsk.meet': 'До зустрічі',
  'whereAsk.notNow': 'Не зараз',
  'whereAsk.failed': 'Не вдалося',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'whereAsk.exact15': 'Exact · 15 min',
  'whereAsk.area60': 'Area · 1 h',
  'whereAsk.meet': 'Until we meet',
  'whereAsk.notNow': 'Not now',
  'whereAsk.failed': "Didn't work",
};
