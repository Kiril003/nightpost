// Standalone translator. MyMemory free API — no auth, ~5000 chars/day.
// When operator is offline this screen still loads (cache hits) but
// new lookups error out. UI surfaces the daily-quota vs network error
// distinction in the toast.

import { useEffect, useRef, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { translate, SUPPORTED_LANGS } from '@/services/translateClient';
import type { PhantomTheme } from '@/types/theme';

interface TranslateScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function TranslateScreen({ themeOverride, framed = false }: TranslateScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const [src, setSrc] = useState('uk');
  const [tgt, setTgt] = useState('en');
  const [text, setText] = useState('');
  const [out, setOut] = useState('');
  const [busy, setBusy] = useState(false);
  const debounceRef = useRef<number | null>(null);

  useEffect(() => {
    if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    if (!text.trim()) {
      setOut('');
      return;
    }
    setBusy(true);
    debounceRef.current = window.setTimeout(async () => {
      try {
        const r = await translate({ text, src, tgt });
        setOut(r.translated);
      } catch (err) {
        useToastStore.getState().push('warn', `Translate: ${(err as Error).message}`);
      } finally {
        setBusy(false);
      }
    }, 600);
  }, [text, src, tgt]);

  const swap = () => {
    setSrc(tgt);
    setTgt(src === 'auto' ? 'en' : src);
    setText(out);
    setOut('');
  };

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '12px 16px 8px' }}>
        <span
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 11,
            letterSpacing: '0.22em',
            color: theme.ink3,
            fontWeight: 700,
            textTransform: 'uppercase',
          }}
        >
          ⇄ переклад
        </span>
      </div>

      <div style={{ flex: 1, padding: '0 12px 14px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
        <GlassCard level="card" theme={theme} style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 10 }}>
          <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
            <select
              value={src}
              onChange={(e) => setSrc(e.target.value)}
              style={{
                flex: 1,
                minHeight: 36,
                padding: '0 10px',
                borderRadius: 10,
                background: theme.glassPanel,
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink,
                fontFamily: 'var(--mono)',
                fontSize: 12,
              }}
            >
              {SUPPORTED_LANGS.map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
            <button
              onClick={swap}
              aria-label="Поміняти"
              style={{
                minWidth: 40,
                minHeight: 36,
                borderRadius: 10,
                background: 'transparent',
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink2,
                fontSize: 14,
              }}
            >
              ⇄
            </button>
            <select
              value={tgt}
              onChange={(e) => setTgt(e.target.value)}
              style={{
                flex: 1,
                minHeight: 36,
                padding: '0 10px',
                borderRadius: 10,
                background: theme.glassPanel,
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink,
                fontFamily: 'var(--mono)',
                fontSize: 12,
              }}
            >
              {SUPPORTED_LANGS.filter((l) => l.code !== 'auto').map((l) => (
                <option key={l.code} value={l.code}>
                  {l.label}
                </option>
              ))}
            </select>
          </div>
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            rows={5}
            placeholder="Текст для перекладу"
            style={{
              padding: 12,
              borderRadius: 12,
              background: theme.surfaceBase,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--sans)',
              fontSize: 14,
              resize: 'vertical',
            }}
          />
        </GlassCard>

        <GlassCard
          level="card"
          theme={theme}
          accentBorder
          accent={theme.primary}
          style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}
        >
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 9,
              letterSpacing: '0.22em',
              color: theme.primary,
              fontWeight: 700,
              textTransform: 'uppercase',
            }}
          >
            {busy ? '◌ перекладаю…' : 'результат'} · via MyMemory
          </div>
          <div
            style={{
              minHeight: 80,
              padding: 10,
              borderRadius: 10,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--sans)',
              fontSize: 14,
              whiteSpace: 'pre-wrap',
            }}
          >
            {out || '—'}
          </div>
          {out && (
            <button
              onClick={async () => {
                try {
                  await navigator.clipboard?.writeText(out);
                  useToastStore.getState().push('success', 'Скопійовано');
                } catch {
                  useToastStore.getState().push('warn', 'Clipboard недоступний');
                }
              }}
              style={{
                minHeight: 36,
                borderRadius: 10,
                background: 'transparent',
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink2,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 700,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
              }}
            >
              ⎘ копіювати
            </button>
          )}
        </GlassCard>
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
