export const uk = {
  'foldRow.count': 'Згорнуто: {n}',
  'foldRow.show': 'Показати',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'foldRow.count': 'Folded: {n}',
  'foldRow.show': 'Show',
};
