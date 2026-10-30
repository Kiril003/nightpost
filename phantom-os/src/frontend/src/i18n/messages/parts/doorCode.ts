export const uk = {
  'doorCode.kind': 'Код',
  'doorCode.until': 'До {when}',
  'doorCode.shut': 'Код прибрано',
  'doorCode.copy': 'Скопіювати',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'doorCode.kind': 'Code',
  'doorCode.until': 'Until {when}',
  'doorCode.shut': 'Code removed',
  'doorCode.copy': 'Copy',
};
