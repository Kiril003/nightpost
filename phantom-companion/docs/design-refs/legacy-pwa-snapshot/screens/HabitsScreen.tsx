// Habits + mood tracker. Two related ideas in one screen:
//   * habits — repeatable activities with day-by-day done grid + streak
//   * mood — single 1-5 entry per day with optional note
// Both pure-local, no phantom-os.

import { useMemo, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import {
  useHabitsStore,
  todayIso,
  computeStreak,
  type MoodEntry,
} from '@/state/habitsStore';
import type { PhantomTheme } from '@/types/theme';

interface HabitsScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

const MOOD_LABEL: Record<MoodEntry['score'], string> = {
  1: '😞',
  2: '🙁',
  3: '😐',
  4: '🙂',
  5: '🤩',
};

const GRID_DAYS = 14;

function lastNDates(n: number): string[] {
  const out: string[] = [];
  const cursor = new Date();
  for (let i = 0; i < n; i++) {
    const iso = `${cursor.getFullYear()}-${String(cursor.getMonth() + 1).padStart(2, '0')}-${String(cursor.getDate()).padStart(2, '0')}`;
    out.push(iso);
    cursor.setDate(cursor.getDate() - 1);
  }
  return out.reverse();
}

export function HabitsScreen({ themeOverride, framed = false }: HabitsScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const habits = useHabitsStore((s) => s.habits);
  const moods = useHabitsStore((s) => s.moods);
  const addHabit = useHabitsStore((s) => s.addHabit);
  const removeHabit = useHabitsStore((s) => s.removeHabit);
  const toggleDone = useHabitsStore((s) => s.toggleDone);
  const setMood = useHabitsStore((s) => s.setMood);

  const [newLabel, setNewLabel] = useState('');
  const [moodNote, setMoodNote] = useState('');
  const today = todayIso();
  const days = useMemo(() => lastNDates(GRID_DAYS), []);
  const todaysMood = moods.find((m) => m.date === today);

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
          ◐ habits + mood
        </span>
      </div>

      <div style={{ flex: 1, padding: '0 12px 14px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 10, minHeight: 0 }}>
        {/* Mood — today's score + note */}
        <GlassCard level="card" theme={theme} accentBorder accent={theme.primary} style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.18em',
              color: theme.primary,
              fontWeight: 700,
              textTransform: 'uppercase',
            }}
          >
            як ти сьогодні?
          </div>
          <div style={{ display: 'flex', gap: 6, justifyContent: 'space-between' }}>
            {[1, 2, 3, 4, 5].map((s) => {
              const score = s as MoodEntry['score'];
              const active = todaysMood?.score === score;
              return (
                <button
                  key={s}
                  onClick={() => setMood(today, score, moodNote)}
                  style={{
                    flex: 1,
                    minHeight: 56,
                    borderRadius: 12,
                    background: active ? theme.primary : theme.glassPanel,
                    color: active ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink,
                    border: `1px solid ${active ? theme.primary : theme.glassBorder}`,
                    fontSize: 22,
                  }}
                >
                  {MOOD_LABEL[score]}
                </button>
              );
            })}
          </div>
          <textarea
            value={todaysMood?.note ?? moodNote}
            onChange={(e) => {
              setMoodNote(e.target.value);
              if (todaysMood) setMood(today, todaysMood.score, e.target.value);
            }}
            rows={2}
            placeholder="Що відчуваєш? (опціонально)"
            style={{
              padding: 10,
              borderRadius: 10,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--sans)',
              fontSize: 13,
              resize: 'vertical',
            }}
          />
          {moods.length > 1 && (
            <div style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3, letterSpacing: '0.14em' }}>
              середнє за {moods.length} днів:{' '}
              {(moods.reduce((a, m) => a + m.score, 0) / moods.length).toFixed(2)} / 5
            </div>
          )}
        </GlassCard>

        {/* Habits — list with grid */}
        <GlassCard level="card" theme={theme} style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <div
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 10,
              letterSpacing: '0.18em',
              color: theme.ink3,
              fontWeight: 700,
              textTransform: 'uppercase',
            }}
          >
            звички
          </div>
          {habits.length === 0 ? (
            <EmptyState
              theme={theme}
              icon="◇"
              title="ще немає звичок"
              hint="Створи нову нижче — кожен день можна одним тапом фіксувати."
              compact
            />
          ) : (
            habits.map((h) => {
              const streak = computeStreak(h);
              return (
                <div key={h.id} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 18 }}>{h.glyph}</span>
                    <span style={{ flex: 1, fontSize: 14, color: theme.ink, fontWeight: 600 }}>
                      {h.label}
                    </span>
                    <span
                      style={{
                        fontFamily: 'var(--mono)',
                        fontSize: 9,
                        color: streak > 0 ? theme.primary : theme.ink3,
                        letterSpacing: '0.14em',
                      }}
                    >
                      {streak > 0 ? `🔥 ${streak}` : '—'}
                    </span>
                    <button
                      onClick={() => {
                        if (confirm(`Видалити "${h.label}"?`)) removeHabit(h.id);
                      }}
                      style={{
                        minWidth: 26,
                        minHeight: 26,
                        borderRadius: 6,
                        background: 'transparent',
                        color: theme.coral,
                        border: `1px solid ${theme.coral}55`,
                        fontSize: 11,
                      }}
                    >
                      ×
                    </button>
                  </div>
                  <div style={{ display: 'flex', gap: 2 }}>
                    {days.map((d) => {
                      const done = h.doneDates.includes(d);
                      const isToday = d === today;
                      return (
                        <button
                          key={d}
                          onClick={() => toggleDone(h.id, d)}
                          aria-label={`${d} ${done ? '✓' : '·'}`}
                          style={{
                            flex: 1,
                            minHeight: 24,
                            borderRadius: 4,
                            background: done ? theme.primary : theme.glassPanel,
                            border: isToday ? `1.5px solid ${theme.primary}` : `1px solid ${theme.glassBorder}`,
                            color: done ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
                            fontFamily: 'var(--mono)',
                            fontSize: 8,
                            padding: 0,
                          }}
                        >
                          {done ? '✓' : ''}
                        </button>
                      );
                    })}
                  </div>
                </div>
              );
            })
          )}
          <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
            <input
              value={newLabel}
              onChange={(e) => setNewLabel(e.target.value)}
              placeholder="Нова звичка"
              style={{
                flex: 1,
                minHeight: 36,
                padding: '0 10px',
                borderRadius: 10,
                background: theme.glassPanel,
                border: `1px solid ${theme.glassBorder}`,
                color: theme.ink,
                fontFamily: 'var(--sans)',
                fontSize: 13,
              }}
            />
            <button
              onClick={() => {
                if (!newLabel.trim()) return;
                addHabit(newLabel);
                setNewLabel('');
              }}
              style={{
                minWidth: 60,
                minHeight: 36,
                borderRadius: 10,
                background: theme.primary,
                color: theme.isDark ? '#1a1208' : '#fff',
                border: `1px solid ${theme.primary}`,
                fontFamily: 'var(--mono)',
                fontSize: 11,
                fontWeight: 700,
                letterSpacing: '0.16em',
                textTransform: 'uppercase',
              }}
            >
              +
            </button>
          </div>
        </GlassCard>
      </div>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}
