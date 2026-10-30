import React from 'react';

/**
 * Знак Nightpost — «Естафета»: одне світло, розрізане навпіл (щілина 2, крок 4
 * на сітці 24), один шлях. Джерело — docs/brand/assets/symbol/nightpost-symbol-24.svg;
 * той самий шлях малює іконка застосунку, заставка (core-design PhantomMark)
 * і `#boot` в index.html. Сторож — identity.test.tsx. Імена експортів лишились
 * старими: їх читає код, а не людина.
 */
export const PHANTOM_MARK_PATH = 'M11 2A8 8 0 0 0 11 18ZM13 6A8 8 0 0 1 13 22Z';

export const PhantomGlyph: React.FC<{ className?: string }> = ({ className = 'w-5 h-5' }) => (
  <svg viewBox="0 0 24 24" className={className} aria-hidden fill="currentColor">
    <path d={PHANTOM_MARK_PATH} />
  </svg>
);

/**
 * Аватар Nightpost:
 * - `inverted` (за замовчуванням): інвертований диск `bg-ink text-canvas` для шапок і входів.
 * - `tonal`: тихий сірий диск `bg-raised text-ink` для рядка у списку «Сьогодні».
 */
export const PhantomMark: React.FC<{ size?: string; className?: string; variant?: 'inverted' | 'tonal' }> = ({
  size = 'w-[52px] h-[52px]',
  className = '',
  variant = 'inverted',
}) => (
  <span
    data-phantom-mark
    role="img"
    aria-label="Nightpost"
    className={`${size} shrink-0 rounded-full ${variant === 'tonal' ? 'bg-raised text-ink' : 'bg-ink text-canvas'} flex items-center justify-center ${className}`}
  >
    <PhantomGlyph className="w-[48%] h-[48%]" />
  </span>
);
