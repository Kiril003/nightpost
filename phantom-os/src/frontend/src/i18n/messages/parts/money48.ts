export const uk = {
  'money48.ask': 'Запросити оплату',
  'money48.file': 'Файл: {name} · {hash}',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'money48.ask': 'Request payment',
  'money48.file': 'File: {name} · {hash}',
};
