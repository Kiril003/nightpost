import { useCallback, useState } from 'react';
import { AlertCircle, CheckCircle2, Loader2, Mail, Send } from 'lucide-react';
import { useLanguage } from '../i18n';
import { waitlistCopy } from '../i18n/waitlist';
import { mailto } from '../lib/company';
import { LICENSE_SERVER } from '../lib/meta';

/**
 * Черга на першу збірку.
 *
 * Доти найзацікавленіша аудиторія впиралась у `mailto:` — тобто йшла нікуди,
 * і ми про неї нічого не знали. Тепер пошта лягає на НАШ сервер: жодного
 * чужого скрипта, піксела чи форми — обіцянка «на цій сторінці не працює
 * аналітика» стосується і нас самих.
 */
type State = 'idle' | 'sending' | 'done' | 'bad' | 'offline';

export default function Waitlist({ platform }: { platform: 'os' | 'desktop' | 'companion' | 'any' }) {
  const { lang } = useLanguage();
  const c = waitlistCopy[lang];
  const [email, setEmail] = useState('');
  const [state, setState] = useState<State>('idle');

  const submit = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      if (state === 'sending') return;
      const value = email.trim();
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) {
        setState('bad');
        return;
      }
      setState('sending');
      try {
        const res = await fetch(`${LICENSE_SERVER}/api/waitlist`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: value, platform, lang }),
        });
        setState(res.ok ? 'done' : 'offline');
      } catch {
        setState('offline');
      }
    },
    [email, lang, platform, state],
  );

  if (state === 'done') {
    return (
      <div className="card flex items-start gap-3 p-5 md:p-6" role="status">
        <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-success" strokeWidth={1.75} aria-hidden />
        <div>
          <p className="text-headline text-ink">{c.doneTitle}</p>
          <p className="mt-1 text-callout text-ink-2">{c.doneBody}</p>
        </div>
      </div>
    );
  }

  const failed = state === 'bad' || state === 'offline';
  const inputId = `wl-${platform}`;
  const errorId = `${inputId}-error`;

  // noValidate: інакше нативна перевірка браузера ковтає submit і показує
  // власну сіру бульбашку чужою мовою — а наше пояснення не з'являється
  // взагалі. Перевірку робимо самі, тим самим виразом, що й сервер.
  return (
    <form onSubmit={submit} noValidate className="card p-5 md:p-6">
      <label htmlFor={inputId} className="block text-footnote font-semibold text-ink-2">
        {c.label}
      </label>
      <div className="mt-2 flex flex-col gap-3 sm:flex-row">
        <input
          id={inputId}
          type="email"
          inputMode="email"
          autoComplete="email"
          value={email}
          onChange={(e) => {
            setEmail(e.target.value);
            if (state !== 'idle') setState('idle');
          }}
          placeholder={c.placeholder}
          aria-invalid={failed ? true : undefined}
          aria-describedby={failed ? errorId : undefined}
          className="h-12 w-full min-w-0 flex-1 rounded-control border border-line-strong bg-raised px-4 text-body text-ink outline-none transition-colors duration-quick placeholder:text-ink-3 focus:border-accent-line focus:ring-1 focus:ring-accent-line focus-visible:outline-none aria-[invalid=true]:border-danger"
        />
        <button type="submit" disabled={state === 'sending'} className="btn-secondary btn-lg shrink-0">
          {state === 'sending' ? (
            <Loader2 className="h-4 w-4 animate-spin" strokeWidth={1.75} aria-hidden />
          ) : (
            <Send className="h-4 w-4" strokeWidth={1.75} aria-hidden />
          )}
          {state === 'sending' ? c.sending : c.submit}
        </button>
      </div>

      {failed && (
        <div id={errorId} role="alert" className="mt-3">
          <p className="flex items-start gap-2 text-callout text-danger">
            <AlertCircle className="mt-[3px] h-4 w-4 shrink-0" strokeWidth={1.75} aria-hidden />
            {state === 'bad' ? c.badEmail : c.offline}
          </p>
          {state === 'offline' && (
            <a
              href={mailto('sales', c.fallbackSubject, `${email}\n\n${platform}`)}
              className="btn-secondary mt-3"
            >
              <Mail className="h-4 w-4" strokeWidth={1.75} aria-hidden />
              {c.fallbackCta}
            </a>
          )}
        </div>
      )}

      <p className="mt-3 text-footnote text-ink-2">{c.promise}</p>
    </form>
  );
}
