export const uk = {
  'coreChat.loading': 'Завантаження…',
  'coreChat.loadFailed': 'Листи не завантажились',
  'coreChat.retry': 'Спробувати ще',
  'coreChat.callAgain': 'Передзвонити',
  'coreChat.close': 'Закрити',
  'coreChat.upTo': 'до {n} МБ',
  'coreChat.schedEvening': 'Сьогодні ввечері',
  'coreChat.schedMorning': 'Завтра зранку',
  'coreChat.schedPick': 'Обрати час',
  'coreChat.replyIn': 'українською',
  'coreChat.testPay': 'Тестові платежі',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'coreChat.loading': 'Loading…',
  'coreChat.loadFailed': "Messages didn't load",
  'coreChat.retry': 'Try again',
  'coreChat.callAgain': 'Call again',
  'coreChat.close': 'Close',
  'coreChat.upTo': 'up to {n} MB',
  'coreChat.schedEvening': 'Tonight',
  'coreChat.schedMorning': 'Tomorrow morning',
  'coreChat.schedPick': 'Pick a time',
  'coreChat.replyIn': 'in English',
  'coreChat.testPay': 'Test payments',
};
