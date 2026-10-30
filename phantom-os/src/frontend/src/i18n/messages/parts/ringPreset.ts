export const uk = {
  'ringPreset.wallpaperFor': 'Це тло: «{ring}»',
  'ringPreset.compactFor': 'Щільні бульбашки: «{ring}»',
  'ringPreset.reset': 'Скинути',
} as const;

export const en: Record<keyof typeof uk, string> = {
  'ringPreset.wallpaperFor': 'This wallpaper for {ring}',
  'ringPreset.compactFor': 'Compact bubbles in {ring}',
  'ringPreset.reset': 'Same as ring',
};
