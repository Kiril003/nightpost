export const uk = {
  'callOverlay.peek': 'Показати',
  'callOverlay.peekHint': 'Камера, поки тримаєте',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'callOverlay.peek': 'Show',
  'callOverlay.peekHint': 'Camera while held',
};
