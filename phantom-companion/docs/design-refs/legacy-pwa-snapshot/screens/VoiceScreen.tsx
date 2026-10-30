import { useEffect, useRef, useState } from 'react';
import { OrbView } from '@/components/OrbView';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { PrimaryActionBar } from '@/components/PrimaryActionBar';
import { Waveform } from '@/components/Waveform';
import { useThemeContext } from '@/theme/ThemeProvider';
import { useStateAccent } from '@/hooks/useStateAccent';
import { useT } from '@/hooks/useT';
import { useVoiceStore } from '@/state/voiceStore';
import { pttDown, pttUp, shutdownVoice } from '@/services/voiceController';
import type { PhantomTheme } from '@/types/theme';

interface VoiceScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function VoiceScreen({ themeOverride, framed = false }: VoiceScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const t = useT();
  const { accent, motionScale } = useStateAccent('DIALOGUE');
  const transcript = useVoiceStore((s) => s.transcript);
  const audioLevel = useVoiceStore((s) => s.audioLevel);
  const ptt = useVoiceStore((s) => s.ptt);
  const clearTranscript = useVoiceStore((s) => s.clear);
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const [showHistory, setShowHistory] = useState(false);

  // Auto-scroll to the latest line whenever transcript grows.
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [transcript.length]);

  useEffect(() => () => shutdownVoice(), []);

  const lastLine = transcript[transcript.length - 1];
  const previewLines = showHistory ? transcript : transcript.slice(-3);

  const ptcLabel = (() => {
    if (ptt === 'Capturing') return t('voice.capturing');
    if (ptt === 'AwaitingFinal') return t('voice.thinking');
    if (ptt === 'Speaking') return t('voice.speaking');
    return t('voice.hold');
  })();

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ flex: 1, padding: '8px 18px 0', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '4px 0 8px',
          }}
        >
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.22em',
              textTransform: 'uppercase',
              color: theme.ink3,
              fontWeight: 700,
            }}
          >
            {transcript.length === 0
              ? 'voice · ready'
              : `voice · ${transcript.length} ${transcript.length === 1 ? 'line' : 'lines'}`}
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            {transcript.length > 3 && (
              <button
                onClick={() => setShowHistory((v) => !v)}
                style={{
                  minHeight: 28,
                  padding: '0 10px',
                  borderRadius: 8,
                  border: `1px solid ${theme.glassBorder}`,
                  background: 'transparent',
                  color: theme.ink3,
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                {showHistory ? 'collapse' : 'history'}
              </button>
            )}
            {transcript.length > 0 && (
              <button
                onClick={clearTranscript}
                style={{
                  minHeight: 28,
                  padding: '0 10px',
                  borderRadius: 8,
                  border: `1px solid ${theme.glassBorder}`,
                  background: 'transparent',
                  color: theme.ink3,
                  fontFamily: 'var(--mono)',
                  fontSize: 9,
                  fontWeight: 700,
                  letterSpacing: '0.18em',
                  textTransform: 'uppercase',
                }}
              >
                clear
              </button>
            )}
          </div>
        </div>

        <div
          ref={scrollRef}
          style={{
            flex: '1 1 auto',
            minHeight: 0,
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'flex-end',
            gap: 6,
            overflowY: 'auto',
            paddingBottom: 8,
          }}
          className="ph-scroll"
        >
          {transcript.length === 0 ? (
            <EmptyState
              theme={theme}
              icon="◌"
              title={t('voice.empty.title')}
              hint={t('voice.empty.hint')}
              compact
            />
          ) : (
            <>
              {previewLines.map((l, i, arr) => {
                const latest = i === arr.length - 1 && l.id === lastLine?.id;
                return (
                  <div
                    key={l.id}
                    style={{
                      fontFamily: l.who === 'ai' ? 'var(--serif)' : 'var(--mono)',
                      fontStyle: l.who === 'ai' ? 'italic' : 'normal',
                      fontSize: latest ? 15 : 13,
                      fontWeight: latest ? 600 : 400,
                      color: latest ? accent : theme.ink2,
                      opacity: latest ? 1 : 0.7,
                      lineHeight: 1.35,
                    }}
                  >
                    <span style={{ fontFamily: 'var(--mono)', fontSize: 9, opacity: 0.5, marginRight: 8 }}>{l.t}</span>
                    {l.text}
                    {l.partial && <span style={{ opacity: 0.5 }}>…</span>}
                  </div>
                );
              })}
              <div ref={bottomRef} />
            </>
          )}
        </div>

        <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '4px 0' }}>
          <OrbView
            accent={accent}
            theme={theme}
            motionScale={motionScale * 1.1}
            size={148}
            ringCount={2}
            audioLevel={audioLevel}
          />
        </div>

        <div style={{ padding: '0 0 6px' }}>
          <Waveform theme={theme} accent={accent} height={28} alwaysDecay />
        </div>
      </div>

      <PrimaryActionBar
        theme={theme}
        accent={accent}
        label={ptcLabel}
        hint={
          ptt === 'Idle'
            ? 'Утримуй щоб говорити · відпусти щоб надіслати'
            : ptt === 'Capturing'
            ? 'Слухаю · відпусти коли скажеш все'
            : ptt === 'AwaitingFinal'
            ? 'Розпізнаю та відповідаю'
            : 'Phantom говорить'
        }
        icon={ptt === 'Speaking' ? '◉' : '◍'}
        onPressDown={() => void pttDown()}
        onPressUp={() => void pttUp()}
        ariaLabel="Push to talk"
      />

      <BottomNav theme={theme} accent={accent} />
    </PhoneShell>
  );
}
