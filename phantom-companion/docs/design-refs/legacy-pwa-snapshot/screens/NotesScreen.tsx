// Local Markdown notes screen — quick capture with no phantom-os
// dependency. Two modes:
//   * list mode  — search + tap-to-edit
//   * edit mode  — title input + textarea body (live preview off by
//                   default to keep the surface fast on weak phones)
//
// Markdown rendered by the inline `renderMarkdown()` helper covers
// headings (#, ##, ###), bullets (-), todo checkboxes ([ ], [x]),
// bold (**), and inline code (`). It's intentionally tiny — the
// operator wants quick capture, not a full publishing tool.

import { useEffect, useMemo, useRef, useState } from 'react';
import { GlassCard } from '@/components/GlassCard';
import { BottomNav } from '@/components/BottomNav';
import { PhoneShell } from '@/components/PhoneShell';
import { EmptyState } from '@/components/EmptyState';
import { useThemeContext } from '@/theme/ThemeProvider';
import {
  useNotesStore,
  searchNotes,
  notePreview,
  type Note,
} from '@/state/notesStore';
import type { PhantomTheme } from '@/types/theme';

function newId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return (crypto as Crypto).randomUUID();
  }
  return `n-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
}

interface NotesScreenProps {
  themeOverride?: PhantomTheme;
  framed?: boolean;
}

export function NotesScreen({ themeOverride, framed = false }: NotesScreenProps) {
  const ctx = useThemeContext();
  const theme = themeOverride ?? ctx.theme;
  const notes = useNotesStore((s) => s.notes);
  const activeId = useNotesStore((s) => s.activeId);
  const setActive = useNotesStore((s) => s.setActive);
  const upsert = useNotesStore((s) => s.upsert);
  const setBody = useNotesStore((s) => s.setBody);
  const rename = useNotesStore((s) => s.rename);
  const remove = useNotesStore((s) => s.remove);
  const togglePin = useNotesStore((s) => s.togglePin);
  const toggleCheckbox = useNotesStore((s) => s.toggleCheckbox);
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => searchNotes(notes, query), [notes, query]);
  const active = useMemo(() => notes.find((n) => n.id === activeId) ?? null, [notes, activeId]);

  const onCreate = () => {
    const id = newId();
    const note: Note = {
      id,
      title: '',
      body: '',
      tags: [],
      pinned: false,
      ts: Date.now(),
      modifiedTs: Date.now(),
    };
    upsert(note);
    setActive(id);
  };

  if (active) {
    return (
      <NoteEditor
        theme={theme}
        framed={framed}
        note={active}
        onClose={() => setActive(null)}
        onTitle={(t) => rename(active.id, t)}
        onBody={(b) => setBody(active.id, b)}
        onTogglePin={() => togglePin(active.id)}
        onToggleCheckbox={(idx) => toggleCheckbox(active.id, idx)}
        onDelete={() => {
          remove(active.id);
          setActive(null);
        }}
      />
    );
  }

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div style={{ padding: '12px 16px 8px', display: 'flex', flexDirection: 'column', gap: 8 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
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
            ≡ нотатки · {notes.length}
          </span>
        </div>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Пошук…"
          style={{
            minHeight: 38,
            padding: '0 12px',
            borderRadius: 10,
            background: theme.glassPanel,
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 13,
          }}
        />
      </div>

      <div
        style={{
          flex: 1,
          padding: '0 12px 14px',
          overflowY: 'auto',
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minHeight: 0,
        }}
      >
        {filtered.length === 0 ? (
          <GlassCard level="card" theme={theme} style={{ padding: 0 }}>
            <EmptyState
              theme={theme}
              icon="≡"
              title="ще немає нотаток"
              hint="Натисни + щоб створити. Працює без phantom-os."
            />
          </GlassCard>
        ) : (
          filtered.map((n) => (
            <button
              key={n.id}
              onClick={() => setActive(n.id)}
              style={{
                textAlign: 'left',
                padding: '12px 14px',
                borderRadius: 12,
                background: theme.glassPanel,
                border: `1px solid ${n.pinned ? theme.primary + '88' : theme.glassBorder}`,
                cursor: 'pointer',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                {n.pinned && <span style={{ fontSize: 10, color: theme.primary }}>★</span>}
                <span
                  style={{
                    fontSize: 14,
                    fontWeight: 600,
                    color: theme.ink,
                    flex: 1,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {n.title.trim() || 'Без назви'}
                </span>
                <span style={{ fontFamily: 'var(--mono)', fontSize: 9, color: theme.ink3 }}>
                  {new Date(n.modifiedTs).toLocaleDateString('uk')}
                </span>
              </div>
              {notePreview(n) && (
                <div
                  style={{
                    fontFamily: 'var(--sans)',
                    fontSize: 12,
                    color: theme.ink3,
                    marginTop: 4,
                    lineHeight: 1.4,
                  }}
                >
                  {notePreview(n)}
                </div>
              )}
            </button>
          ))
        )}
      </div>

      <button
        onClick={onCreate}
        aria-label="Нова нотатка"
        style={{
          position: 'absolute',
          right: 16,
          bottom: 76,
          width: 56,
          height: 56,
          borderRadius: 18,
          background: `radial-gradient(circle at 30% 25%, ${theme.primary}, ${theme.primary}dd)`,
          color: theme.isDark ? '#1a1208' : '#fff',
          border: 'none',
          fontSize: 28,
          fontWeight: 300,
          lineHeight: 1,
          boxShadow: `0 10px 24px ${theme.primary}55`,
          zIndex: 5,
        }}
      >
        +
      </button>

      <BottomNav theme={theme} accent={theme.primary} />
    </PhoneShell>
  );
}

interface NoteEditorProps {
  theme: PhantomTheme;
  framed: boolean;
  note: Note;
  onClose: () => void;
  onTitle: (title: string) => void;
  onBody: (body: string) => void;
  onTogglePin: () => void;
  onToggleCheckbox: (lineIdx: number) => void;
  onDelete: () => void;
}

function NoteEditor({
  theme,
  framed,
  note,
  onClose,
  onTitle,
  onBody,
  onTogglePin,
  onToggleCheckbox,
  onDelete,
}: NoteEditorProps) {
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const [showPreview, setShowPreview] = useState(false);
  useEffect(() => {
    if (!note.title && !note.body) {
      // Empty notes auto-focus the title field on open.
      const el = document.querySelector<HTMLInputElement>('#note-title');
      el?.focus();
    }
  }, [note.id]);

  return (
    <PhoneShell theme={theme} framed={framed}>
      <div
        style={{
          padding: '10px 12px',
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          borderBottom: `1px solid ${theme.glassBorder}`,
        }}
      >
        <button
          onClick={onClose}
          aria-label="Назад"
          style={{
            width: 36,
            height: 36,
            borderRadius: 10,
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontSize: 18,
          }}
        >
          ←
        </button>
        <input
          id="note-title"
          value={note.title}
          onChange={(e) => onTitle(e.target.value)}
          placeholder="Назва"
          style={{
            flex: 1,
            minHeight: 36,
            padding: '0 10px',
            borderRadius: 10,
            background: 'transparent',
            border: `1px solid ${theme.glassBorder}`,
            color: theme.ink,
            fontFamily: 'var(--sans)',
            fontSize: 14,
            fontWeight: 600,
          }}
        />
        <button
          onClick={onTogglePin}
          aria-label={note.pinned ? 'Зняти з топу' : 'Закріпити'}
          style={{
            width: 36,
            height: 36,
            borderRadius: 10,
            background: note.pinned ? theme.primary : 'transparent',
            color: note.pinned ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
            border: `1px solid ${theme.glassBorder}`,
            fontSize: 14,
          }}
        >
          ★
        </button>
        <button
          onClick={() => setShowPreview((v) => !v)}
          aria-label="Перегляд"
          style={{
            width: 36,
            height: 36,
            borderRadius: 10,
            background: showPreview ? theme.primary : 'transparent',
            color: showPreview ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink2,
            border: `1px solid ${theme.glassBorder}`,
            fontSize: 14,
          }}
        >
          ⤴
        </button>
      </div>

      <div style={{ flex: 1, minHeight: 0, padding: '10px 12px', overflowY: 'auto' }}>
        {showPreview ? (
          <div
            style={{
              fontFamily: 'var(--sans)',
              fontSize: 14,
              color: theme.ink,
              lineHeight: 1.55,
              whiteSpace: 'pre-wrap',
            }}
          >
            {renderMarkdown(note.body, theme, onToggleCheckbox)}
          </div>
        ) : (
          <textarea
            ref={bodyRef}
            value={note.body}
            onChange={(e) => onBody(e.target.value)}
            placeholder="Текст. Підтримує # heading, - bullet, [ ] todo, **bold**, `code`."
            style={{
              width: '100%',
              minHeight: 300,
              padding: 12,
              borderRadius: 12,
              background: theme.glassPanel,
              border: `1px solid ${theme.glassBorder}`,
              color: theme.ink,
              fontFamily: 'var(--mono)',
              fontSize: 13,
              lineHeight: 1.5,
              resize: 'vertical',
            }}
          />
        )}
      </div>

      <div
        style={{
          padding: '8px 12px calc(env(safe-area-inset-bottom, 8px) + 8px)',
          borderTop: `1px solid ${theme.glassBorder}`,
          display: 'flex',
          gap: 6,
        }}
      >
        <button
          onClick={() => {
            if (confirm('Видалити нотатку?')) onDelete();
          }}
          style={{
            flex: 1,
            minHeight: 38,
            borderRadius: 10,
            background: 'transparent',
            color: theme.coral,
            border: `1px solid ${theme.coral}55`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          видалити
        </button>
        <button
          onClick={onClose}
          style={{
            flex: 2,
            minHeight: 38,
            borderRadius: 10,
            background: theme.primary,
            color: theme.isDark ? '#1a1208' : '#fff',
            border: `1px solid ${theme.primary}`,
            fontFamily: 'var(--mono)',
            fontSize: 11,
            fontWeight: 700,
            letterSpacing: '0.18em',
            textTransform: 'uppercase',
          }}
        >
          готово
        </button>
      </div>
    </PhoneShell>
  );
}

/** Tiny markdown renderer — headings / bullets / todos / bold / inline code.
 *  Returns React fragments rather than dangerouslySetInnerHTML so we
 *  don't have to sanitise user input. */
function renderMarkdown(
  body: string,
  theme: PhantomTheme,
  onToggleCheckbox: (idx: number) => void,
): React.ReactNode {
  const lines = body.split('\n');
  return lines.map((line, idx) => {
    if (/^#\s/.test(line)) {
      return (
        <div key={idx} style={{ fontSize: 22, fontWeight: 700, color: theme.ink, marginTop: 8 }}>
          {line.replace(/^#\s/, '')}
        </div>
      );
    }
    if (/^##\s/.test(line)) {
      return (
        <div key={idx} style={{ fontSize: 18, fontWeight: 700, color: theme.ink, marginTop: 6 }}>
          {line.replace(/^##\s/, '')}
        </div>
      );
    }
    if (/^###\s/.test(line)) {
      return (
        <div key={idx} style={{ fontSize: 15, fontWeight: 700, color: theme.ink, marginTop: 4 }}>
          {line.replace(/^###\s/, '')}
        </div>
      );
    }
    const todoMatch = line.match(/^(\s*[-*]\s*)\[( |x|X)\]\s?(.*)$/);
    if (todoMatch) {
      const checked = todoMatch[2]!.toLowerCase() === 'x';
      return (
        <div
          key={idx}
          style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '2px 0' }}
        >
          <button
            onClick={() => onToggleCheckbox(idx)}
            style={{
              width: 18,
              height: 18,
              flexShrink: 0,
              marginTop: 4,
              borderRadius: 4,
              background: checked ? theme.primary : 'transparent',
              color: checked ? (theme.isDark ? '#1a1208' : '#fff') : theme.ink3,
              border: `1px solid ${checked ? theme.primary : theme.glassBorder}`,
              cursor: 'pointer',
              padding: 0,
              fontSize: 12,
              lineHeight: 1,
            }}
          >
            {checked ? '✓' : ''}
          </button>
          <span
            style={{
              flex: 1,
              color: checked ? theme.ink3 : theme.ink,
              textDecoration: checked ? 'line-through' : 'none',
            }}
          >
            {renderInline(todoMatch[3] ?? '', theme)}
          </span>
        </div>
      );
    }
    if (/^[-*]\s/.test(line)) {
      return (
        <div key={idx} style={{ paddingLeft: 14, color: theme.ink }}>
          • {renderInline(line.replace(/^[-*]\s/, ''), theme)}
        </div>
      );
    }
    if (line.trim() === '') return <div key={idx} style={{ height: 8 }} />;
    return <div key={idx}>{renderInline(line, theme)}</div>;
  });
}

function renderInline(line: string, theme: PhantomTheme): React.ReactNode {
  const out: React.ReactNode[] = [];
  let i = 0;
  while (i < line.length) {
    if (line.startsWith('**', i)) {
      const end = line.indexOf('**', i + 2);
      if (end > i + 2) {
        out.push(
          <strong key={`b${i}`} style={{ fontWeight: 700 }}>
            {line.slice(i + 2, end)}
          </strong>,
        );
        i = end + 2;
        continue;
      }
    }
    if (line[i] === '`') {
      const end = line.indexOf('`', i + 1);
      if (end > i + 1) {
        out.push(
          <code
            key={`c${i}`}
            style={{
              fontFamily: 'var(--mono)',
              fontSize: 12,
              padding: '1px 4px',
              background: theme.surfaceBase,
              borderRadius: 4,
              border: `1px solid ${theme.glassBorder}`,
            }}
          >
            {line.slice(i + 1, end)}
          </code>,
        );
        i = end + 1;
        continue;
      }
    }
    // Plain char run — gather until next special token.
    let end = i;
    while (end < line.length && line[end] !== '*' && line[end] !== '`') end++;
    if (line[end] === '*' && line[end + 1] !== '*') end++;
    out.push(line.slice(i, end));
    i = end;
  }
  return out;
}
