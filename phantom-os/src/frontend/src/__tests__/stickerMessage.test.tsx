import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, render, waitFor } from '@testing-library/react';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const anim = { play: vi.fn(), pause: vi.fn(), goToAndStop: vi.fn(), destroy: vi.fn() };
const loadAnimation = vi.fn(() => anim);
vi.mock('../stickers/lottieRuntime', () => ({ loadLottie: () => Promise.resolve({ loadAnimation }) }));

import { buildFeedRows, densityOf } from '../components/messenger/feed/feedModel';
import { MessageRow } from '../components/messenger/feed/MessageRow';
import { FeedActionsContext, type FeedActions } from '../components/messenger/feed/feedActions';
import { plainText } from '../services/plainText';
import { kindLine } from '../components/messenger/chatListFormat';
import { composeSticker, parseSticker } from '../stickers/stickerWire';
import { PACK, loadSticker, stickerForEmoji } from '../stickers/pack';
import { POSTER_FRAME } from '../stickers/StickerView';
import { ThreadMessageBody } from '../components/messenger/ThreadView';
import { isLowSignal } from '../services/signalFold';
import type { Message } from '../types/messenger';

const actions = new Proxy({}, { get: () => () => {} }) as FeedActions;
const tea = composeSticker({ packId: 'd1', id: 'tea', emoji: '☕' });

const msgOf = (text: string): Message =>
  ({
    id: 'm1', senderId: 'u2', senderName: 'Оля', senderAvatar: '', text, timestamp: '14:32',
    sentAt: '2026-10-15T14:32:00Z', type: 'text', isSelf: false,
  }) as Message;

const renderRow = (text: string) => {
  const [row] = buildFeedRows([msgOf(text)], 'me', new Date('2026-10-15T15:00:00Z'));
  return render(
    <FeedActionsContext.Provider value={actions}>
      <MessageRow
        row={row} chatId="c1" currentUserId="me" currentUserName="Я" groupish={false} direct phone
        isSelectionMode={false} isSelected={false} isHighlighted={false} isSearchMatch={false}
        isActiveSearchMatch={false} fresh={false} expanded={false} copied={false}
      />
    </FeedActionsContext.Provider>,
  );
};

let reduce = false;
beforeEach(() => {
  reduce = false;
  vi.clearAllMocks();
  window.matchMedia = ((q: string) => ({ matches: reduce && q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as unknown as typeof window.matchMedia;
});
afterEach(cleanup);

describe('стікер — звичайний текстовий лист з міткою', () => {
  it('туди й назад: емодзі першим рядком, мітка другим', () => {
    expect(tea).toBe('☕\n⟦st1:d1/tea⟧');
    expect(parseSticker(tea)).toEqual({ packId: 'd1', id: 'tea', emoji: '☕' });
  });

  it('чужий текст із міткою — не стікер', () => {
    expect(parseSticker('привіт\n⟦st1:d1/tea⟧')).toBeNull();
    expect(parseSticker('☕\n⟦st1:d1/tea⟧\nще')).toBeNull();
    expect(parseSticker('☕\n⟦st1:D1/../x⟧')).toBeNull();
    expect(parseSticker('☕')).toBeNull();
  });

  it('превʼю, пошук і цитата — без мітки', () => {
    const p = plainText(tea);
    expect(p).not.toContain('⟦');
    expect(p).toMatch(/^☕ \S+/);
  });

  it('щільність — прозора бульбашка, як самотній смайл', () => {
    expect(densityOf(msgOf(tea))).toBe('emoji-single');
  });

  it('рядок вузла з родом «sticker» — емодзі й слово, як у вебі', () => {
    const word = plainText(tea).split(' ')[1];
    expect(kindLine('sticker', null, '☕')).toBe(`☕ ${word}`);
    expect(kindLine('sticker', null, tea)).toBe(`☕ ${word}`);
    expect(kindLine('sticker', null, null)).toBe(word);
  });

  it('стікер згортається, як «ок» чи смайл', () => {
    expect(isLowSignal(msgOf(tea))).toBe(true);
  });
});

describe('набір D1 лежить у застосунку', () => {
  const dir = join(__dirname, '../stickers/d1');

  it('щонайменше 12 стікерів, кожен — файл Lottie 512×512, 60 к/с, кадр-постер усередині', () => {
    expect(PACK.length).toBeGreaterThanOrEqual(12);
    const files = readdirSync(dir).filter((f) => f.endsWith('.json'));
    for (const s of PACK) {
      expect(files).toContain(`${s.id}.json`);
      const j = JSON.parse(readFileSync(join(dir, `${s.id}.json`), 'utf8'));
      expect([j.w, j.h, j.fr]).toEqual([512, 512, 60]);
      expect(j.op).toBeGreaterThan(POSTER_FRAME);
      expect(j.layers.length).toBeGreaterThan(0);
      expect(statSync(join(dir, `${s.id}.json`)).size).toBeLessThan(16 * 1024);
    }
  });

  it('стікер знаходиться без мережі', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    for (const s of PACK) expect(await loadSticker(s.packId, s.id)).toHaveProperty('layers');
    expect(await loadSticker('d1', 'nope')).toBeNull();
    expect(fetchSpy).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it('самотнє емодзі знаходить живого двійника, з варіантом і без', () => {
    expect(stickerForEmoji('❤️')?.id).toBe('heart');
    expect(stickerForEmoji('❤')?.id).toBe('heart');
    expect(stickerForEmoji('🦄')).toBeNull();
  });

  it('програвач не потрапляє в перший шматок: жодного статичного імпорту lottie-web', () => {
    const walk = (d: string): string[] =>
      readdirSync(d).flatMap((f) => {
        const p = join(d, f);
        if (statSync(p).isDirectory()) return f === '__tests__' ? [] : walk(p);
        return /\.(ts|tsx)$/.test(f) ? [p] : [];
      });
    const bad = walk(join(__dirname, '..')).filter((p) => /^import\s+(?!type\b)[^;]*from\s+['"]lottie-web/m.test(readFileSync(p, 'utf8')));
    expect(bad).toEqual([]);
  });
});

describe('стікер у стрічці', () => {
  it('отриманий стікер малюється анімацією з набору, без тексту мітки', async () => {
    const { container } = renderRow(tea);
    const el = container.querySelector('[data-sticker="d1/tea"]');
    expect(el).not.toBeNull();
    expect(container.textContent).not.toContain('⟦');
    await waitFor(() => expect(el?.getAttribute('data-sticker-state')).toBe('on'));
    expect(loadAnimation).toHaveBeenCalledWith(expect.objectContaining({ renderer: 'svg', loop: true }));
    expect(anim.play).toHaveBeenCalled();
  });

  it('рух вимкнено — нерухомий кадр, без програвання', async () => {
    reduce = true;
    const { container } = renderRow(tea);
    await waitFor(() => expect(container.querySelector('[data-sticker-state="on"]')).not.toBeNull());
    expect(anim.goToAndStop).toHaveBeenCalledWith(POSTER_FRAME, true);
    expect(anim.play).not.toHaveBeenCalled();
  });

  it('невідомий стікер — велике емодзі', async () => {
    const { container } = renderRow('🦊\n⟦st1:d1/fox⟧');
    const el = container.querySelector('[data-sticker="d1/fox"]');
    await waitFor(() => expect(el?.getAttribute('data-sticker-state')).toBe('off'));
    expect(el?.textContent).toContain('🦊');
    expect(loadAnimation).not.toHaveBeenCalled();
  });

  it('у гілці — той самий стікер, без мітки', async () => {
    const { container } = render(<ThreadMessageBody msg={msgOf(tea)} isSelf={false} onOpenLightbox={() => {}} />);
    expect(container.querySelector('[data-sticker="d1/tea"]')).not.toBeNull();
    expect(container.textContent).not.toContain('⟦');
    await waitFor(() => expect(container.querySelector('[data-sticker-state="on"]')).not.toBeNull());
  });

  it('рядок зник — анімацію знищено', async () => {
    const { container, unmount } = renderRow(tea);
    await waitFor(() => expect(container.querySelector('[data-sticker-state="on"]')).not.toBeNull());
    act(() => unmount());
    expect(anim.destroy).toHaveBeenCalled();
  });
});
