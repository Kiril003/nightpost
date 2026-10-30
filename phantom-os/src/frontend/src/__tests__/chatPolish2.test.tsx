import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FailedMark } from '../components/messenger/feed/feedParts';
import { tNow as t } from '../components/chat/tNow';

describe('A6: невдалий лист — один рядок', () => {
  it('знак не переноситься і ріжеться, а не росте в два рядки', () => {
    render(<FailedMark msgId="m1" />);
    const mark = screen.getByTestId('msg-failed');
    expect(mark.className).toContain('whitespace-nowrap');
    expect(mark.className).toContain('max-w-full');
    expect(mark.querySelector('span')?.className).toContain('truncate');
  });
});

describe('A8: слова без канцеляриту', () => {
  it('порожній чат — «Повідомлень ще немає»', () => {
    expect(t('chatArea.noLetters')).toBe('Повідомлень ще немає');
  });
  it('відповідь має двокрапку: «Відповідь: Оксана»', () => {
    expect(t('messageComposer.replyTo', { name: 'Оксана' })).toBe('Відповідь: Оксана');
  });
});

import { composerHeight } from '../components/messenger/MessageComposer';

describe('A7: поле з 6+ рядків росте цілими рядками', () => {
  it('висота кратна рядку плюс поля, не 140 навпіл рядка', () => {
    const el = document.createElement('textarea');
    el.style.lineHeight = '22px';
    el.style.paddingTop = '11px';
    el.style.paddingBottom = '11px';
    document.body.appendChild(el);
    expect(composerHeight(900, el)).toBe(11 + 5 * 22);
    expect(composerHeight(60, el)).toBe(60);
    el.remove();
  });
});

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

describe('B4/B5: пошук', () => {
  const read = (p: string) => readFileSync(join(__dirname, '..', p), 'utf8');
  it('B4: чіпи фільтрів — один рядок із горизонтальною прокруткою, не перенос', () => {
    const src = read('components/messenger/GlobalSearch.tsx');
    expect(src).toMatch(/data-search-filters[\s\S]{0,200}flex-nowrap[^"]*overflow-x-auto/);
    expect(src).not.toMatch(/data-search-filters[\s\S]{0,200}flex-wrap/);
  });
  it('B5: поле пошуку ріже підказку трикрапкою при великому шрифті', () => {
    expect(read('components/messenger/kit/AskField.tsx')).toMatch(/<input[\s\S]*?truncate/);
  });
});

import { buildFeedRows } from '../components/messenger/feed/feedModel';
import type { Message } from '../types/messenger';

describe('A5: черга одного автора тримається 10 хвилин', () => {
  const m = (id: string, sender: string, sentAt: string) =>
    ({ id, senderId: sender, senderName: sender, senderAvatar: '', timestamp: '12:00', sentAt, type: 'text', isSelf: false, text: id }) as Message;
  const today = new Date('2026-03-20T20:00:00Z');
  it('7 хвилин — та сама черга, 11 — нова, інший автор рве чергу', () => {
    const rows = buildFeedRows(
      [m('a', 'p', '2026-03-20T08:00:00Z'), m('b', 'p', '2026-03-20T08:07:00Z'), m('c', 'p', '2026-03-20T08:18:00Z'), m('d', 'q', '2026-03-20T08:19:00Z')],
      'me',
      today,
    );
    expect(rows[1].isFirstInGroup).toBe(false);
    expect(rows[2].isFirstInGroup).toBe(true);
    expect(rows[3].isFirstInGroup).toBe(true);
  });
});
