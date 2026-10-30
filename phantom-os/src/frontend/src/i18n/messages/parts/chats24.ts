export const uk = {
  'chats24.blockedPresent': 'Тут є заблокована людина',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chats24.blockedPresent': 'Someone you blocked is here',
};
