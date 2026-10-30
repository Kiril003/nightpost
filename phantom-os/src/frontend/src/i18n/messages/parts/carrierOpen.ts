export const uk = {
  'secureMediaBubble.opening': 'Відкриваємо',
  'secureMediaBubble.partMissing': 'Частина файла ще не доїхала',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'secureMediaBubble.opening': 'Opening',
  'secureMediaBubble.partMissing': 'Part of the file has not arrived yet',
};
