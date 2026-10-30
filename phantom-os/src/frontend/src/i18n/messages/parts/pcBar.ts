export const uk = {
  'pcBar.call': 'Дзвінок',
  'pcBar.running': 'Працює',
  'pcBar.connected': "На зв'язку",
  'pcBar.notConnected': "Не на зв'язку",
  'pcBar.new': 'Нове',
  'pcBar.lost': 'Не відповідає',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'pcBar.call': 'Call',
  'pcBar.running': 'Running',
  'pcBar.connected': 'Connected',
  'pcBar.notConnected': 'Not connected',
  'pcBar.new': 'New',
  'pcBar.lost': 'Not responding',
};
