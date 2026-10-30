export const uk = {
  'mediaC6.carrierUnreadable': 'Файл не прочитався',
  'mediaC6.forwardNoCarrier': 'Сюди цей файл не переслати',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'mediaC6.carrierUnreadable': 'File unreadable',
  'mediaC6.forwardNoCarrier': "Can't send this file here",
};
