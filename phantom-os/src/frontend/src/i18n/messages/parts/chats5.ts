export const uk = {
  'chats5.times': 'Час',
  'chats5.addTime': 'Додати час',
  'chats5.removeTime': 'Прибрати {time}',
  'chats5.confirm': 'Підтвердити',
  'chats5.noDay': 'Немає даних дня',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chats5.times': 'Times',
  'chats5.addTime': 'Add time',
  'chats5.removeTime': 'Remove {time}',
  'chats5.confirm': 'Confirm',
  'chats5.noDay': 'No day data',
};
