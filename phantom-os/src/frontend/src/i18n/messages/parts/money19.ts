export const uk = {
  'money19.gotCash': 'Готівку отримано',
  'money19.undoCash': 'Скасувати готівку',
  'money19.cashShare': 'готівкою',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'money19.gotCash': 'Got cash',
  'money19.undoCash': 'Undo cash',
  'money19.cashShare': 'cash',
};
