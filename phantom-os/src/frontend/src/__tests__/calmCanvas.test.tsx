import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DEFAULT_LOOK, normalizeLook, useLook } from '../services/appearance';
import { ChatWallpaper } from '../components/messenger/ChatWallpaper';
import { ChatArea } from '../components/messenger/ChatArea';
import { CallSettingsSheet } from '../components/messenger/CallSettingsSheet';
import { chipTone } from '../components/messenger/kit/FilterChip';
import { useMessengerStore } from '../stores/messengerStore';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { contrast } from '../styles/formTokens';
import type { Chat, Message } from '../types/messenger';

const ROOT = process.cwd();
const REPO = join(ROOT, '../../..');
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');
const TOKENS = JSON.parse(readFileSync(join(REPO, 'docs/design/system/tokens.json'), 'utf8'));

// contour.webp: сірі лінії 128, найщільніші — альфа 140/255 (magick histogram).
const LINE_GRAY = 128;
const LINE_ALPHA = 140 / 255;
const rgb = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
const hex = (c: number[]) => `#${c.map((v) => Math.round(v).toString(16).padStart(2, '0')).join('')}`;
const over = (base: string, top: number[], a: number) => hex(rgb(base).map((b, i) => b + (top[i] - b) * a));

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});
afterEach(() => {
  cleanup();
  useLook.setState({ look: DEFAULT_LOOK });
  vi.restoreAllMocks();
});

describe('тихе полотно: типовий візерунок тихіший за підписи', () => {
  it('обраний контур не сильніший за wall-dot з D і далеко під text-3, День і Ніч', () => {
    useLook.setState({ look: normalizeLook({ wallpaper: { kind: 'builtin', id: 'contour' }, wallpaperPicked: true }) });
    render(<ChatWallpaper chatId="any" />);
    const layer = screen.getByTestId('chat-wallpaper');
    expect(layer.dataset.kind).toBe('builtin');
    for (const theme of ['day', 'night'] as const) {
      const t = (k: string): string => TOKENS.color[theme][k].value;
      const scrim = Number(layer.style.getPropertyValue(`--wp-dim-${theme}`));
      const line = contrast(over(t('canvas'), [LINE_GRAY, LINE_GRAY, LINE_GRAY], (1 - scrim) * LINE_ALPHA), t('canvas'));
      const dot = t('wall-dot');
      const wallDot = contrast(over(t('canvas'), rgb(dot), parseInt(dot.slice(7, 9), 16) / 255), t('canvas'));
      expect(line, `${theme}: візерунок ${line.toFixed(3)} > wall-dot ${wallDot.toFixed(3)}`).toBeLessThanOrEqual(wallDot * 1.01);
      expect(line).toBeLessThan(contrast(t('text-3'), t('canvas')) / 4);
    }
  });

  it('фото людини лишається з її власним приглушенням', () => {
    useLook.setState({ look: normalizeLook({ wallpaper: { kind: 'photo', url: '/webmsgr/_look/chat-abc.jpg' }, wallpaperPicked: true, dim: { day: 20, night: 40 } }) });
    render(<ChatWallpaper chatId="any" />);
    const layer = screen.getByTestId('chat-wallpaper');
    expect(layer.style.getPropertyValue('--wp-dim-day')).toBe('0.2');
    expect(layer.style.getPropertyValue('--wp-dim-night')).toBe('0.4');
  });

  it('телефон і веб глушать візерунок на ту саму міру', () => {
    const kt = readFileSync(join(REPO, 'phantom-companion/app/src/main/java/local/phantom/companion/data/appearance/Appearance.kt'), 'utf8');
    const web = read('src/services/appearance.ts');
    const k = kt.match(/PATTERN_QUIET = ([0-9.]+)f/)?.[1];
    const w = web.match(/PATTERN_QUIET = ([0-9.]+);/)?.[1];
    expect(k).toBeTruthy();
    expect(Number(k)).toBe(Number(w));
  });

  it('вступ асистента — картка поверхні, а не діра кольору полотна у візерунку', () => {
    expect(read('src/styles/wallpaper.css')).toMatch(/\.ph-stage-card \{[^}]*background-color: var\(--ph-surface\)/);
  });
});

const noop = () => {};
const talk = (n: number): Message[] =>
  Array.from({ length: n }, (_, i) =>
    ({
      id: `m${i}`, senderId: 'peer', senderName: 'Оксана', senderAvatar: '', timestamp: '14:32',
      type: 'text', isSelf: false, text: `лист ${i}`,
    }) as unknown as Message,
  );

describe('відкриття розмови з непрочитаними', () => {
  it('приземлення на перший непрочитаний — без бурштинової плити і без кнопки «вниз» силою', async () => {
    const into = vi.fn();
    Element.prototype.scrollIntoView = into;
    useMessengerStore.setState({ unreadAtOpen: {} } as never);
    const { container } = render(
      <MemoryRouter>
        <ChatArea
          currentChat={{ id: 'c-land', title: 'Оксана', avatar: '', type: 'dm', circle: 'all', pinned: false, unreadCount: 2, messages: [] } as unknown as Chat}
          messages={talk(5)}
          currentUserId="me"
          onOpenLocation={noop} onVotePoll={noop} onPayBillShare={noop} onAddReaction={noop} onReplyMessage={noop}
          selectedMessageIds={[]} onToggleSelectMessage={noop} isSelectionMode={false} isSearching={false} onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    await waitFor(() => expect(into).toHaveBeenCalled());
    expect(container.querySelector('[data-testid="unread-divider"]')).not.toBeNull();
    expect(container.querySelector('.message-row.bg-accent-soft')).toBeNull();
    expect(container.querySelector('[data-testid="scroll-bottom"]')).toBeNull();
  });
});

describe('одна мова «обраного»', () => {
  it('сегменти налаштувань дзвінків обрані так само, як фільтр-чип', () => {
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'en' } }));
    render(<CallSettingsSheet active={false} readDataUsedBytes={() => null} />);
    const on = within(screen.getByRole('group', { name: 'On Wi-Fi' })).getAllByRole('button').find((b) => b.getAttribute('aria-pressed') === 'true')!;
    for (const c of chipTone(true).split(' ')) expect(on.className).toContain(c);
    useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: 'uk' } }));
  });

  it('жоден чип чи сегмент не обирається чорною плитою', () => {
    for (const f of ['CallSettingsSheet', 'SharedSection', 'MomentsSettings', 'MomentsStrip', 'MomentAudienceSheet']) {
      expect(read(`src/components/messenger/${f}.tsx`), f).not.toMatch(/\? 'bg-ink text-canvas' : 'bg-raised/);
    }
  });

  it('інформація про людину: назад — ←, як на інших екранах; «Змінити» не другий бурштин', () => {
    const src = read('src/components/messenger/PeerProfileSheet.tsx');
    expect(src).not.toMatch(/<ChevronLeft/);
    expect(src).toMatch(/<InfoView/);
    expect(src).not.toMatch(/onClick=\{startRename\}[^>]*text-accent-text/);
  });
});
