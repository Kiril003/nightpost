/**
 * Профіль › Вигляд у вебі: акцент через токени, тло розмови з приглушенням і
 * розмиттям для Дня й Ночі, щільність, своє тло розмови з меню ⋮ — і все це
 * окремо для кожної своєї особи.
 */
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  ACCENTS,
  DEFAULT_LOOK,
  WALLPAPERS,
  normalizeLook,
  startLookWatch,
  useLook,
  wallpaperPhoto,
} from '../services/appearance';
import { ChatWallpaper } from '../components/messenger/ChatWallpaper';
import { PhoneChatMenu } from '../components/messenger/PhoneChatMenu';
import { useMessengerStore } from '../stores/messengerStore';
import { contrast } from '../styles/formTokens';
import type { Chat } from '../types/messenger';

const ROOT = process.cwd();
const REPO = join(ROOT, '../../..');
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');
const TOKENS = JSON.parse(readFileSync(join(REPO, 'docs/design/system/tokens.json'), 'utf8'));
const CSS = read('src/styles/phantom/tokens.css');
const IDENTITY = read('src/styles/identity.css');
const WALLPAPER = read('src/styles/wallpaper.css');

const asUser = (id: string) =>
  useMessengerStore.setState((s) => ({ currentUser: { ...s.currentUser, id } }));
const setBridge = (getAppearance?: () => string) => {
  const w = window as unknown as { PhantomBridge?: unknown };
  if (getAppearance) w.PhantomBridge = { getAppearance };
  else delete w.PhantomBridge;
};

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});
afterEach(() => {
  cleanup();
  setBridge();
  localStorage.clear();
  useLook.setState({ look: DEFAULT_LOOK });
  for (const a of ['data-accent', 'data-bubbles', 'data-list-density']) document.documentElement.removeAttribute(a);
  vi.restoreAllMocks();
});

describe('одне джерело', () => {
  it('акценти — у порядку tokens.json, тло — ті самі файли, що в бандлі й на рідному боці', () => {
    expect([...ACCENTS]).toEqual(TOKENS.accents.order);
    const files = readdirSync(join(ROOT, 'public/wallpapers')).filter((f) => f.endsWith('.webp')).map((f) => f.replace('.webp', '')).sort();
    expect(files).toEqual([...WALLPAPERS].sort());
    const kt = readFileSync(join(REPO, 'phantom-companion/app/src/main/java/local/phantom/companion/data/appearance/Appearance.kt'), 'utf8');
    expect(kt).toContain(`listOf(${WALLPAPERS.map((w) => `"${w}"`).join(', ')})`);
  });

  it('PhantomTokens.kt — байт у байт вихід генератора разом з акцентами', () => {
    const built = readFileSync(join(REPO, 'docs/design/system/build/PhantomTokens.kt'), 'utf8');
    const app = readFileSync(join(REPO, 'phantom-companion/core-design/src/main/java/local/phantom/companion/core/design/tokens/PhantomTokens.kt'), 'utf8');
    expect(app).toBe(built);
    expect(app).toContain('val PhantomAccents = listOf(');
  });

  it('кожен акцент перекриває --ph-accent у Ночі й у Дні значенням із tokens.json', () => {
    for (const id of ACCENTS.filter((a) => a !== 'amber')) {
      for (const [sel, theme] of [[`[data-accent='${id}']:not([data-theme='day'])`, 'night'], [`[data-accent='${id}'][data-theme='day']`, 'day']] as const) {
        const block = CSS.slice(CSS.indexOf(`${sel} {`)).split('}')[0];
        expect(block, `${sel} нема в tokens.css`).toContain('--ph-accent:');
        expect(block).toContain(`--ph-accent: ${TOKENS.accents[id][theme].accent};`);
        expect(block).toContain(`--ph-on-accent: ${TOKENS.accents[id][theme]['on-accent']};`);
      }
    }
  });
});

describe('вибір і сховище', () => {
  it('чуже й поламане падає на типове, числа в межах', () => {
    expect(DEFAULT_LOOK.wallpaper).toEqual({ kind: 'none' });
    expect(normalizeLook({}).wallpaper).toEqual({ kind: 'none' });
    expect(normalizeLook({ wallpaper: { kind: 'none' } }).wallpaper).toEqual({ kind: 'none' });
    expect(normalizeLook({ wallpaper: { kind: 'none' }, wallpaperPicked: true }).wallpaper).toEqual({ kind: 'none' });
    const l = normalizeLook({
      accent: 'neon',
      wallpaper: { kind: 'photo', url: 'javascript:alert(1)' },
      chats: { a: { kind: 'builtin', id: 'lava' }, b: { kind: 'builtin', id: 'clay' } },
      dim: { day: -4, night: 500 },
      blur: { day: 3.4, night: 'x' },
      bubbles: 'huge',
      list: 'compact',
    });
    expect(l.accent).toBe('amber');
    expect(l.wallpaper).toEqual({ kind: 'none' });
    expect(l.chats).toEqual({ b: { kind: 'builtin', id: 'clay' } });
    expect(l.dim).toEqual({ day: 0, night: 80 });
    expect(l.blur).toEqual({ day: 3, night: 0 });
    expect(l.bubbles).toBe('comfortable');
    expect(l.list).toBe('compact');
  });

  it('на ПК своє тло розмови переживає перезапуск і живе окремо для кожної особи', async () => {
    asUser('me-a');
    useLook.getState().refresh();
    await useLook.getState().setChatWallpaper('c1', { kind: 'builtin', id: 'slate' });
    useLook.setState({ look: DEFAULT_LOOK });
    useLook.getState().refresh();
    expect(useLook.getState().look.chats.c1).toEqual({ kind: 'builtin', id: 'slate' });

    const off = startLookWatch();
    act(() => asUser('me-b'));
    expect(useLook.getState().look.chats).toEqual({});
    act(() => asUser('me-a'));
    expect(useLook.getState().look.chats.c1).toEqual({ kind: 'builtin', id: 'slate' });
    off();
  });

  it('на телефоні вигляд береться з мосту і оновлюється поштовхом рідного боку', () => {
    let look = { accent: 'sea', bubbles: 'compact', list: 'compact' };
    setBridge(() => JSON.stringify(look));
    const off = startLookWatch();
    const root = document.documentElement;
    expect(root.getAttribute('data-accent')).toBe('sea');
    expect(root.getAttribute('data-bubbles')).toBe('compact');
    expect(root.getAttribute('data-list-density')).toBe('compact');
    look = { accent: 'rose', bubbles: 'comfortable', list: 'comfortable' };
    act(() => (window as unknown as { __phantomAppearance: () => void }).__phantomAppearance());
    expect(root.getAttribute('data-accent')).toBe('rose');
    expect(root.getAttribute('data-list-density')).toBe('comfortable');
    off();
    expect((window as unknown as { __phantomAppearance?: unknown }).__phantomAppearance).toBeUndefined();
  });

  it('фото з галереї стискається до JPEG ≤ 1440 і йде як data-URL', async () => {
    const encode = vi.fn(async () => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }));
    const deps = { decode: async () => ({ width: 4000, height: 3000, source: {}, close: () => undefined }), encode };
    const url = await wallpaperPhoto(new Blob([new Uint8Array([9])], { type: 'image/png' }), deps);
    expect(encode).toHaveBeenCalledWith({}, 1440, 1080, 'image/jpeg', 0.82);
    expect(url).toBe('data:image/jpeg;base64,AQID');
    expect(await wallpaperPhoto(new Blob(['x'], { type: 'text/plain' }), deps)).toBeNull();
  });
});

describe('тло в розмові', () => {
  it('типове тло розмови — рівний папір чи графіт, без ізоліній', () => {
    render(<ChatWallpaper chatId="other" />);
    expect(document.querySelector('[data-testid="chat-wallpaper"][data-kind="builtin"]')).toBeNull();
    expect(document.querySelector('[style*="contour.webp"]')).toBeNull();
  });

  it('рельєф лишається вибором: обраний — малюється, нав\'язаний рідним боком без вибору — ні', () => {
    const contour = { kind: 'builtin', id: 'contour' };
    expect(normalizeLook({ wallpaper: contour, wallpaperPicked: false }).wallpaper).toEqual({ kind: 'none' });
    expect(normalizeLook({ wallpaper: contour }).wallpaper).toEqual({ kind: 'none' });
    expect(normalizeLook({ wallpaper: contour, wallpaperPicked: true }).wallpaper).toEqual(contour);
    useLook.setState({ look: normalizeLook({ wallpaper: contour, wallpaperPicked: true }) });
    render(<ChatWallpaper chatId="other" />);
    const layer = document.querySelector('[data-testid="chat-wallpaper"][data-kind="builtin"]') as HTMLElement;
    expect((layer.firstElementChild as HTMLElement).style.backgroundImage).toContain('wallpapers/contour.webp');
  });

  it('приглушення й розмиття Дня й Ночі доходять до шару тла; своє тло розмови перекриває загальне', () => {
    useLook.setState({
      look: normalizeLook({
        wallpaper: { kind: 'builtin', id: 'sand' },
        wallpaperPicked: true,
        chats: { quiet: { kind: 'none' }, mine: { kind: 'photo', url: '/webmsgr/_look/chat-abc.jpg' } },
        dim: { day: 12, night: 55 },
        blur: { day: 0, night: 6 },
      }),
    });
    const { rerender } = render(<ChatWallpaper chatId="other" />);
    const layer = screen.getByTestId('chat-wallpaper');
    expect(layer.style.getPropertyValue('--wp-dim-day')).toBe('0.56');
    expect(layer.style.getPropertyValue('--wp-dim-night')).toBe('0.775');
    expect(layer.style.getPropertyValue('--wp-blur-night')).toBe('6px');
    expect((layer.firstElementChild as HTMLElement).style.backgroundImage).toContain('wallpapers/sand.webp');
    rerender(<ChatWallpaper chatId="mine" />);
    expect((screen.getByTestId('chat-wallpaper').firstElementChild as HTMLElement).style.backgroundImage).toContain('/webmsgr/_look/chat-abc.jpg');
    rerender(<ChatWallpaper chatId="quiet" />);
    expect(screen.queryByTestId('chat-wallpaper')).toBeNull();
  });

  it('читається на будь-якому тлі: суцільні бульбашки, табличка полотна під рядками стрічки', () => {
    for (const theme of ['night', 'day'] as const) {
      const t = (k: string): string => TOKENS.color[theme][k].value;
      for (const fill of ['self', 'surface', 'canvas']) expect(t(fill), `${theme}.${fill} прозорий`).toMatch(/^#[0-9A-F]{6}$/i);
      expect(contrast(t('text-3'), t('canvas'))).toBeGreaterThanOrEqual(4.5);
      expect(contrast(t('on-self'), t('self'))).toBeGreaterThanOrEqual(4.5);
      expect(contrast(t('text'), t('surface'))).toBeGreaterThanOrEqual(4.5);
    }
    expect(WALLPAPER).toMatch(/\[data-wallpaper='on'\] \.ph-on-stage \{[^}]*background-color: var\(--ph-canvas\)/);
    expect(WALLPAPER).toMatch(/\.ph-wallpaper-scrim \{[^}]*background-color: var\(--ph-canvas\);[^}]*opacity: var\(--wp-dim\)/);
    expect(WALLPAPER).toMatch(/html:root\[data-theme='day'\] \.ph-wallpaper \{[^}]*--wp-dim: var\(--wp-dim-day\)/);
    const blurs = [...WALLPAPER.matchAll(/^([^{}\n]+)\{[^}]*blur\(/gm)].map((m) => m[1].trim());
    expect(blurs, 'розмиття — лише на фото тла').toEqual(['.ph-wallpaper-img']);
    expect(WALLPAPER).not.toMatch(/gradient\(|backdrop-filter|box-shadow/);
    for (const f of ['src/components/messenger/feed/MessageRow.tsx', 'src/components/messenger/ChatArea.tsx']) {
      expect(read(f)).toContain('ph-on-stage');
    }
  });

  it('щільність бульбашок і списку — лише через атрибут кореня', () => {
    expect(IDENTITY).toMatch(/\[data-chat-bubbles='compact'\] \.msg-density-long,/);
    expect(IDENTITY).toMatch(/html:root\[data-bubbles='compact'\] :is\(\.msg-density-short, \.msg-density-medium, \.msg-density-long\):not\(\[data-chat-bubbles\] \*\) \{\s*padding: 5px 10px;/);
    expect(IDENTITY).toMatch(/html:root\[data-list-density='compact'\] \.ph-list-row \{ height: 60px; \}/);
    expect(read('src/components/messenger/PhoneChatList.tsx').match(/h-\[72px\] ph-list-row/g)).toHaveLength(1);
    expect(read('src/components/messenger/kit/ListRow.tsx').match(/h-\[72px\] ph-list-row/g)).toHaveLength(1);
    expect(read('public/boot-theme.js')).toContain("root.setAttribute('data-accent', look.accent)");
  });

  it('досяжність: розмова вмикає вигляд і кладе шар тла під стрічку', () => {
    const rootSrc = read('src/components/messenger/MessengerRoot.tsx');
    expect(rootSrc).toContain('useEffect(() => startLookWatch(), []);');
    expect(rootSrc).toMatch(/data-wallpaper=\{hasWallpaper \? 'on' : undefined\}>\s*<ChatWallpaper chatId=\{activeChat\.id\} \/>/);
  });
});

describe('меню ⋮ › Тло', () => {
  const dm = { id: 'peer_ok', title: 'Оксана', type: 'dm', peerNodeId: 'a1b2c3d4e5f60718', messages: [], unreadCount: 0 } as unknown as Chat;

  it('своє тло розмови: вбудоване, жодного, знову як усюди', async () => {
    asUser('me-a');
    render(<PhoneChatMenu chat={dm} onClose={() => {}} onOpenCard={() => {}} onOpenGroupDetails={() => {}} />);
    fireEvent.click(screen.getByText('Тло'));
    expect(screen.getByTestId('chat-wallpaper-picker')).toBeTruthy();
    fireEvent.click(document.querySelector('[data-wallpaper-id="clay"]') as HTMLElement);
    await waitFor(() => expect(useLook.getState().look.chats.peer_ok).toEqual({ kind: 'builtin', id: 'clay' }));
    expect(document.querySelector('[data-wallpaper-id="clay"]')?.getAttribute('aria-pressed')).toBe('true');
    fireEvent.click(screen.getByText('Без тла'));
    await waitFor(() => expect(useLook.getState().look.chats.peer_ok).toEqual({ kind: 'none' }));
    fireEvent.click(screen.getByText('Як усюди'));
    await waitFor(() => expect(useLook.getState().look.chats.peer_ok).toBeUndefined());
  });

  it('на телефоні вибір іде рідному боку', async () => {
    setBridge(() => '{}');
    const call = vi.fn((_m: string, _p: string, _b: string | null) =>
      JSON.stringify({ ok: true, status: 200, data: { chats: { peer_ok: { kind: 'builtin', id: 'dusk' } } } }));
    (window as unknown as { PhantomBridge: Record<string, unknown> }).PhantomBridge.call = call;
    await useLook.getState().setChatWallpaper('peer_ok', { kind: 'builtin', id: 'dusk' });
    expect(call).toHaveBeenCalledWith('PUT', '/messenger/appearance/chats/peer_ok', JSON.stringify({ kind: 'builtin', id: 'dusk' }));
    expect(useLook.getState().look.chats.peer_ok).toEqual({ kind: 'builtin', id: 'dusk' });
  });
});
