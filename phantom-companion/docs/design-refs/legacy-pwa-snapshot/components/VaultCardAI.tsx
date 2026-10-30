// Card-anchored AI thread — inline expander for the Vault server
// cards section. Keeps history client-side per session; backend
// builds the preamble server-side so secrets never travel.

import { useState } from 'react';
import { askCard, type CardAskTurn } from '@/services/vaultCardAI';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';

interface VaultCardAIProps {
  cardId: string;
  cardLabel: string;
  /** Suggested seed prompts shown above the input. Tap appends to
   *  the input box rather than auto-sending. */
  suggestions?: string[];
}

const DEFAULT_SUGGESTIONS = [
  'Поясни цю картку коротко',
  'Що звідси можна автоматизувати?',
  'Допиши план як ввести ці дані у форму',
];

export function VaultCardAI({ cardId, cardLabel, suggestions = DEFAULT_SUGGESTIONS }: VaultCardAIProps) {
  const { theme } = useThemeContext();
  const [open, setOpen] = useState(false);
  const [history, setHistory] = useState<CardAskTurn[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [provider, setProvider] = useState<string | null>(null);

  const send = async () => {
    const q = input.trim();
    if (!q || busy) return;
    setInput('');
    setBusy(true);
    const userTurn: CardAskTurn = { role: 'user', content: q };
    setHistory((h) => [...h, userTurn]);
    try {
      const out = await askCard(cardId, q, history);
      setProvider(out.provider);
      setHistory((h) => [...h, { role: 'assistant', content: out.answer }]);
    } catch (err) {
      useToastStore.getState().push('error', `AI: ${(err as Error).message}`);
      setHistory((h) => [...h, { role: 'assistant', content: '⚠️ Не вдалось запитати модель.' }]);
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <button
        onClick={() => setOpen(true)}
        style={{
          marginTop: 8,
          padding: '6px 12px',
          borderRadius: 999,
          background: 'transparent',
          color: theme.primary,
          border: `1px solid ${theme.primary}55`,
          fontFamily: 'var(--mono)',
          fontSize: 10,
          fontWeight: 700,
          letterSpacing: '0.18em',
          textTransform: 'uppercase',
        }}
      >
        ◇ Запитати AI
      </button>
    );
  }

  return (
    <div
      style={{
        marginTop: 10,
        padding: 10,
        borderRadius: 12,
        border: `1px solid ${theme.glassBorder}`,
        background: theme.glassPanel,
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span
          style={{
            fontFamily: 'var(--mono)',
            fontSize: 9,
            letterSpacing: '0.22em',
            textTransform: 'uppercase',
            color: theme.primary,
            fontWeight: 700,
          }}
        >
          AI · {cardLabel}
        </span>
        {provider && (
          <span
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 9,
              color: theme.ink3,
              letterSpacing: '0.18em',
              textTransform: 'uppercase',
            }}
          >
            via {provider}
          </span>
        )}
        <button
          onClick={() => setOpen(false)}
          aria-label="Close"
          style={{
            marginLeft: 'auto',
            background: 'transparent',
            border: 'none',
            color: theme.ink3,
            fontSize: 14,
            cursor: 'pointer',
          }}
        >
          ×
        </button>
      </div>

      {history.length === 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {suggestions.map((s) => (
            <button
              key={s}
              onClick={() => setInput((cur) => (cur ? `${cur} ${s}` : s))}
              style={{
                padding: '4px 10px',
                borderRadius: 999,
                background: 'transparent',
                color: theme.ink2,
                border: `1px solid ${theme.glassBorder}`,
                fontFamily: 'var(--mono)',
                fontSize: 10,
                fontWeight: 600,
                letterSpacing: '0.06em',
              }}
            >
              {s}
            </button>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {history.map((t, i) => (
          <div
            key={i}
            style={{
              fontFamily: t.role === 'assistant' ? 'var(--serif)' : 'var(--mono)',
              fontStyle: t.role === 'assistant' ? 'italic' : 'normal',
              fontSize: 13,
              color: t.role === 'assistant' ? theme.ink : theme.ink2,
              lineHeight: 1.45,
            }}
          >
            <span style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3, marginRight: 6, letterSpacing: '0.18em', textTransform: 'uppercase' }}>
              {t.role === 'assistant' ? 'AI' : 'я'}
            </span>
            {t.content}
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 6 }}>
        <input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !busy) {
              e.preventDefault();
              void send();
            }
          }}
          placeholder="Спитай про цю картку…"
          style={{
            flex: 1,
            minHeight: 36,
            padding: '0 12px',
            borderRadius: 10,
            background: theme.surfaceBase,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 13,
          }}
          disabled={busy}
        />
        <button
          onClick={() => void send()}
          disabled={busy || !input.trim()}
          style={{
            minHeight: 36,
            padding: '0 12px',
            borderRadius: 10,
            background: theme.primary,
            color: theme.isDark ? '#1a1208' : '#fff',
            border: `1px solid ${theme.primary}`,
            fontFamily: 'var(--mono)',
            fontSize: 10,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
            opacity: busy || !input.trim() ? 0.5 : 1,
          }}
        >
          {busy ? '…' : 'send'}
        </button>
      </div>
    </div>
  );
}
