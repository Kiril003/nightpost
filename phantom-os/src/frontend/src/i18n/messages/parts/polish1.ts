export const uk = {
  'polish1.myComputer': 'Мій компʼютер',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'polish1.myComputer': 'My computer',
};
