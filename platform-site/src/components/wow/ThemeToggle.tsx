import { Moon, Sun } from 'lucide-react';
import { useLanguage } from '../../i18n';
import { wowCopy } from '../../i18n/wow';
import { setTheme, useTheme } from '../../lib/theme';

export default function ThemeToggle() {
  const { lang } = useLanguage();
  const theme = useTheme();
  const next = theme === 'night' ? 'day' : 'night';
  const label = next === 'day' ? wowCopy[lang].nav.toDay : wowCopy[lang].nav.toNight;
  return (
    <button type="button" className="tt" aria-label={label} title={label} onClick={() => setTheme(next)}>
      {theme === 'night' ? <Sun strokeWidth={1.75} aria-hidden /> : <Moon strokeWidth={1.75} aria-hidden />}
    </button>
  );
}
