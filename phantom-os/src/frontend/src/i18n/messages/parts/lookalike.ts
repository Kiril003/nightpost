export const uk = {
  'lookalike.notThe': 'Не той {name}, якого ви знаєте',
  'lookalike.someoneElse': 'Інша людина',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'lookalike.notThe': 'Not the {name} you know',
  'lookalike.someoneElse': 'Someone else',
};
