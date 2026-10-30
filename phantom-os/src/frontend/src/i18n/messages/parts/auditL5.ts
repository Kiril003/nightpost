export const uk = {
  'callsHub.title': 'Дзвінки',
  'callsHub.forAllChats': 'Дзвінки · для всіх розмов',
  'callsHub.allCalls': 'Для всіх дзвінків',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callsHub.title': 'Calls',
  'callsHub.forAllChats': 'Calls · all chats',
  'callsHub.allCalls': 'For all calls',
};
