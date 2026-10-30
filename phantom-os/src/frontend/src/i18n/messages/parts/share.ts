export const uk = {
  'shareIn.title': 'Надіслати в розмову',
  'shareIn.send': 'Надіслати',
  'shareIn.photos': 'Фото: {n}',
  'shareIn.paymentLink': 'Посилання на оплату',
  'shareIn.skipped': 'Не додано: {n}',
  'shareIn.failed': 'Не вдалося надіслати',
  'shareIn.place': 'Місце',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'shareIn.title': 'Send to chat',
  'shareIn.send': 'Send',
  'shareIn.photos': 'Photos: {n}',
  'shareIn.paymentLink': 'Payment link',
  'shareIn.skipped': 'Not added: {n}',
  'shareIn.failed': 'Couldnʼt send',
  'shareIn.place': 'Place',
};
