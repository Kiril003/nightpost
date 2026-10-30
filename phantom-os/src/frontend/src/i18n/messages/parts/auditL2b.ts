export const uk = {
  'chatRow.noLetters': 'Ще немає повідомлень',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'chatRow.noLetters': 'No messages yet',
};
