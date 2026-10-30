/**
 * Мала частина дизайн-системи, яку треба знати КОДУ (а не CSS): тон людини
 * і вимір контрасту.
 *
 * Барви, ролі тексту, радіуси й рух живуть у docs/design/system/tokens.json →
 * src/styles/phantom/{tokens.css,tailwind-preset.ts} (згенеровано, сторож —
 * src/__tests__/designTokens.test.ts). Тут їх більше не дублюємо: чотири фази,
 * вісім «куль» і горизонталі під стрічкою прибрано (AUDIT.md №5–7).
 */

export type ThemeName = 'night' | 'day';
export const THEMES: ThemeName[] = ['night', 'day'];

/**
 * Шість плоских приглушених тонів людей (§2.1, «Person tones»). Лише для
 * монограм і імені автора в групі; бурштиновий сектор оминається свідомо.
 * Барви — `--ph-person-N` / `--ph-person-N-ink`.
 */
export const PEOPLE_TONES = 6;

/** Сталий номер тону з ключа (id вузла, id розмови): FNV-1a, 32 біти. */
export const toneIndex = (key: string): number => {
  let h = 0x811c9dc5;
  for (let i = 0; i < key.length; i += 1) {
    h ^= key.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0) % PEOPLE_TONES;
};

const channel = (c: number): number => {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
};

export const luminance = (hex: string): number => {
  const n = parseInt(hex.slice(1, 7), 16);
  return 0.2126 * channel((n >> 16) & 255) + 0.7152 * channel((n >> 8) & 255) + 0.0722 * channel(n & 255);
};

/** Відношення контрасту WCAG 2.x. */
export const contrast = (a: string, b: string): number => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};
