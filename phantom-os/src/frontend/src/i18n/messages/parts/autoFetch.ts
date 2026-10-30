export const uk = {
  'autoFetch.tap': 'Завантажити · {size}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'autoFetch.tap': 'Load · {size}',
};
