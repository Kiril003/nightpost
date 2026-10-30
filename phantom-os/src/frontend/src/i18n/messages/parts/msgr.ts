export const uk = {
  'msgr.jumpDate': 'Перейти до дня',
  'msgr.export': 'Експорт розмови',
  'msgr.exportMedia': 'медіа',
  'msgr.exportFailed': 'Не вдалося експортувати',
  'msgr.exportCopied': 'Скопійовано в буфер',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'msgr.jumpDate': 'Jump to date',
  'msgr.export': 'Export chat',
  'msgr.exportMedia': 'media',
  'msgr.exportFailed': 'Export failed',
  'msgr.exportCopied': 'Copied to clipboard',
};
