export const uk = {
  'chatSound.ruleIn': '{rule} · {circle}',
  'chatSound.scopeRingGeneric': 'Усі розмови',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chatSound.ruleIn': '{rule} · {circle}',
  'chatSound.scopeRingGeneric': 'All chats',
};
