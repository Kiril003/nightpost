import { useLanguage } from '../i18n';
import { LANGS } from '../i18n/types';

/** Перемикач мови: дві текстові кнопки без пігулки. */
export default function LanguageToggle() {
  const { lang, setLang } = useLanguage();

  return (
    <div role="group" aria-label="Language / Мова" className="inline-flex items-center gap-1">
      {LANGS.map(({ code, label }) => (
        <button
          key={code}
          type="button"
          onClick={() => setLang(code)}
          aria-pressed={lang === code}
          className={`inline-flex min-h-[40px] items-center justify-center rounded-control px-2 text-subhead transition-colors duration-quick ${
            lang === code ? 'text-ink' : 'text-ink-3 hover:text-ink'
          }`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}
