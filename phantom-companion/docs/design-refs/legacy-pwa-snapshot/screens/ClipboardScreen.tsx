// Clipboard history surface. The phone's OS clipboard is single-slot;
// once you copy a fresh value, the previous one is gone. This screen
// captures pastes / manual saves and lets the operator dredge back.

import { useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useToastStore } from '@/state/toastStore';
import { useClipboardStore } from '@/state/clipboardStore';
import type { PhantomTheme } from '@/types/theme';

interface ClipboardScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function ClipboardScreen({ themeOverride, framed = false }: ClipboardScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const entries = useClipboardStore((s) => s.entries);
  const capture = useClipboardStore((s) => s.capture);
  const remove = useClipboardStore((s) => s.remove);
  const togglePin = useClipboardStore((s) => s.togglePin);
  const [draft, setDraft] = useState('');

  const onPasteFromOs = async () => {
    try {
      const text = await navigator.clipboard?.readText();
      if (!text || !text.trim()) {
        useToastStore.getState().push('info', 'OS clipboard порожній');
        return;
      }
      capture(text);
      useToastStore.getState().push('success', 'Захоплено');
    } catch {
      useToastStore.getState().push('warn', 'OS clipboard недоступний — введи вручну');
    }
  };

  const onCopyToOs = async (text: string) => {
    try {
      await navigator.clipboard?.writeText(text);
      useToastStore.getState().push('success', 'Скопійовано в OS clipboard');
    } catch {
      useToastStore.getState().push('warn', 'Clipboard write failed');
    }
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
          ⎘ clipboard · {entries.length}
        </span>
      </div>

      <div style={{ padding: '0 12px 8px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', gap: 6 }}>
          <input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            placeholder="Вписати або вставити сюди"
            style={{
              flex: 1,
              minHeight: 38,
              padding: '0 12px',
              borderRadius: 10,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 12,
            }}
          />
          <button
            onClick={() => {
              if (!draft.trim()) return;
              capture(draft);
              setDraft('');
              useToastStore.getState().push('success', 'Збережено');
            }}
            style={{
              minWidth: 60,
              minHeight: 38,
              borderRadius: 10,
              background: theme.primary,
              color: theme.isDark ? '#1a1208' : '#fff',
              border: `1px solid ${theme.primary}`,
              fontFamily: 'var(--mono)',
              fontSize: 10,
              fontWeight: 700,
              letterSpacing: '0.16em',
              textTransform: 'uppercase',
            }}
          >
            +
          </button>
        </div>
        <button
          onClick={() => void onPasteFromOs()}
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
          ↓ захопити з os clipboard
        </button>
      </div>

      <div
        style={{
          flex: 1,
          padding: '0 12px 14px',
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 6,
          minHeight: 0,
        }}
      >
        {entries.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="⎘"
              title="історія порожня"
              hint="Вставляй сюди коли OS буфер от-от перетреш."
            />
          </GlassCard>
        ) : (
          entries.map((e) => (
            <GlassCard
              key={e.id}
              level="card"
              theme={theme}
              accentBorder={e.pinned}
              accent={theme.primary}
              style={{ padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}
            >
              <div
                style={{
                  fontFamily: 'var(--mono)',
                  fontSize: 12,
                  color: theme.ink,
                  whiteSpace: 'pre-wrap',
                  wordBreak: 'break-word',
                  maxHeight: 100,
                  overflowY: 'auto',
                }}
              >
                {e.text}
              </div>
              <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                <span
                  style={{
                    fontFamily: 'var(--mono)',
                    fontSize: 9,
                    color: theme.ink3,
                    flex: 1,
                  }}
                >
                  {new Date(e.ts).toLocaleString('uk')}
                </span>
                <button
                  onClick={() => togglePin(e.id)}
                  style={{
                    minWidth: 30,
                    minHeight: 28,
                    borderRadius: 6,
                    background: e.pinned ? theme.primary : 'transparent',
                    color: e.pinned ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
                    border: `1px solid ${e.pinned ? theme.primary : theme.glassBorder}`,
                    fontSize: 11,
                  }}
                >
                  ★
                </button>
                <button
                  onClick={() => void onCopyToOs(e.text)}
                  style={{
                    minWidth: 30,
                    minHeight: 28,
                    borderRadius: 6,
                    background: 'transparent',
                    border: `1px solid ${theme.glassBorder}`,
                    color: theme.ink2,
                    fontSize: 11,
                  }}
                >
                  ⎘
                </button>
                <button
                  onClick={() => remove(e.id)}
                  style={{
                    minWidth: 30,
                    minHeight: 28,
                    borderRadius: 6,
                    background: 'transparent',
                    border: `1px solid ${theme.coral}55`,
                    color: theme.coral,
                    fontSize: 11,
                  }}
                >
                  ×
                </button>
              </div>
            </GlassCard>
          ))
        )}
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
