export const uk = {
  'chats7.title': 'Що ще змінити',
  'chats7.where': 'Не показувати, де я',
  'chats7.moments': 'Прибрати з моментів',
  'chats7.pin': 'Відкріпити',
  'chats7.quiet': 'Не будити вночі',
  'chats7.apply': 'Застосувати',
  'chats7.skip': 'Лишити як є',
  'chats7.failed': 'Не вдалося',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chats7.title': 'Also change',
  'chats7.where': 'Stop sharing where I am',
  'chats7.moments': 'Remove from moments',
  'chats7.pin': 'Unpin',
  'chats7.quiet': "Don't wake me at night",
  'chats7.apply': 'Apply',
  'chats7.skip': 'Keep as is',
  'chats7.failed': 'Failed',
};
