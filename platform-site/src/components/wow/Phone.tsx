import type { CSSProperties } from 'react';
import { useLanguage } from '../../i18n';
import { wowCopy, type Screen } from '../../i18n/wow';
import { useTheme } from '../../lib/theme';

export function screenSrc(screen: Screen, lang: string, theme: 'day' | 'night') {
  return `/wow/scr/${screen}-${lang}-${theme === 'night' ? 'dark' : 'light'}.webp`;
}

export default function Phone({ screen, width, eager = false }: { screen: Screen; width: number; eager?: boolean }) {
  const { lang } = useLanguage();
  const theme = useTheme();
  return (
    <div className="phone" style={{ '--pw': `${width}px` } as CSSProperties}>
      <div className="scr">
        <img
          src={screenSrc(screen, lang, theme)}
          alt={wowCopy[lang].alt[screen]}
          width={1024}
          height={2216}
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
        />
      </div>
      <img className="fr" src={`/wow/frame-${theme}.webp`} alt="" width={600} height={1200} loading={eager ? 'eager' : 'lazy'} />
      <span className="cam" aria-hidden />
    </div>
  );
}
