export const uk = {
  'lastPaid.line': 'Минулий рахунок: {name} · {date}',
  'lastPaid.you': 'ви',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'lastPaid.line': 'Last bill: {name} · {date}',
  'lastPaid.you': 'you',
};
