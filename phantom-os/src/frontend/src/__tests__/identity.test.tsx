/**
 * Сторож ідентичності PHANTOM (02.10) — шару, який робить месенджер САМЕ нашим.
 *
 * Червоніти вміє кожен пункт:
 *  - пружини в tokens.css розійшлися з k/ζ у tokens.json (переписано руками);
 *  - зерно стало важчим за 2 КБ або в identity.css з'явився градієнт/розмиття;
 *  - рельєф став випадковим (не з зерна) чи однаковим для різних районів;
 *  - світло доби перекрило контраст тексту на полотні;
 *  - печатка грає на старих листах; дорога листа малюється без даних вузла;
 *  - мікромініатюра пропускає не-data:image чи завелика;
 *  - знак у вебі розійшовся зі знаком у docs/design/system/mark;
 *  - підказки PHANTOM не надсилають питання (недосяжні з порожньої розмови).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { terrain, terrainSeed } from '../services/terrain';
import { sealOnce, RouteDiagram } from '../components/messenger/feed/feedParts';
import { voiceBars } from '../components/messenger/UnifiedVoiceBubble';
import { isMicroThumb, MICRO_MAX_CHARS } from '../services/imagePrep';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';
import { PHANTOM_MARK_PATH } from '../components/messenger/PhantomMark';
import { ChatArea, PHANTOM_STARTERS } from '../components/messenger/ChatArea';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat } from '../types/messenger';

const ROOT = process.cwd();
const REPO = join(ROOT, '../../..');
const read = (p: string) => readFileSync(join(ROOT, p), 'utf8');
const TOKENS = JSON.parse(readFileSync(join(REPO, 'docs/design/system/tokens.json'), 'utf8'));
const IDENTITY = read('src/styles/identity.css');
const TOKENS_CSS = read('src/styles/phantom/tokens.css');

afterEach(() => cleanup());

describe('рух: пружини з біблії 05 §1.3', () => {
  // Незалежний вивід тієї ж кривої — не імпорт генератора.
  const at = (k: number, z: number, t: number) => {
    const w = Math.sqrt(k);
    if (z >= 1) return 1 - Math.exp(-w * t) * (1 + w * t);
    const wd = w * Math.sqrt(1 - z * z);
    return 1 - Math.exp(-z * w * t) * (Math.cos(wd * t) + ((z * w) / wd) * Math.sin(wd * t));
  };
  const BIBLE: Record<string, [number, number]> = {
    press: [700, 0.9], layer: [400, 0.86], flight: [260, 0.78], snap: [180, 0.75], breath: [120, 0.85],
  };

  it('k і ζ у tokens.json — рівно таблиця біблії', () => {
    for (const [name, [k, z]] of Object.entries(BIBLE)) {
      expect(TOKENS.motion.springs[name].stiffness, name).toBe(k);
      expect(TOKENS.motion.springs[name].dampingRatio, name).toBe(z);
    }
  });

  it('linear() у tokens.css — вибірка саме цієї пружини; перекид лише в тих, що «приземляються»', () => {
    for (const [name, [k, z]] of Object.entries(BIBLE)) {
      const ms = Number(new RegExp(`--ph-spring-${name}-ms: (\\d+)ms;`).exec(TOKENS_CSS)?.[1]);
      const pts = (new RegExp(`--ph-spring-${name}: linear\\(([^)]*)\\);`).exec(TOKENS_CSS)?.[1] ?? '').split(',').map(Number);
      expect(pts.length, name).toBe(41);
      expect(pts[0]).toBe(0);
      expect(pts[40]).toBe(1);
      for (const i of [5, 10, 20, 30]) expect(pts[i], `${name}@${i}`).toBeCloseTo(at(k, z, (ms / 1000) * (i / 40)), 3);
      // Перекид помітний (> 1 %) лише в тих, що «приземляються» (flight, snap);
      // аркуші й екрани (ζ ≥ 0.86) — у межах піксела.
      const over = Math.max(...pts) > 1.01;
      expect(over, name).toBe(z < 0.8);
    }
  });

  it('identity.css: пружини лише під @supports linear(), скорочений рух — 120 мс згасання', () => {
    expect(IDENTITY).toMatch(/@supports \(animation-timing-function: linear\(0, 1\)\) \{[\s\S]*--ph-spring-flight/);
    expect(IDENTITY).toMatch(/prefers-reduced-motion: reduce\)[\s\S]*f-fade-in 120ms linear both !important/);
  });
});

describe('тло: зерно, рельєф, ніяких ШІ-тропів', () => {
  it('зерно — SVG data URI < 2 КБ у кожній темі', () => {
    const grains = [...IDENTITY.matchAll(/--ph-grain: (url\("data:image\/svg\+xml,[^"]+"\));/g)].map((m) => m[1]);
    expect(grains).toHaveLength(2);
    for (const g of grains) expect(g.length).toBeLessThan(2048);
  });

  it('у шарі ідентичності немає градієнтів, розмиття, скла й сяйва', () => {
    const rules = IDENTITY.replace(/\/\*[\s\S]*?\*\//g, '');
    expect(rules).not.toMatch(/gradient\(|backdrop-filter|filter:\s*blur|blur\(/);
    expect(rules).not.toMatch(/box-shadow:[^;]*rgba?\([^)]*(242|169|59)/);
  });

  it('рельєф — з зерна: той самий район дає ті самі лінії, інший — інші', () => {
    const a = terrain(terrainSeed(50.45, 30.52), 200, 300);
    const b = terrain(terrainSeed(50.4501, 30.5199), 200, 300);
    const c = terrain(terrainSeed(49.84, 24.03), 200, 300);
    expect(a.lines.length).toBeGreaterThan(1000);
    expect(a.index.length).toBeGreaterThan(100);
    expect(b.lines).toBe(a.lines);
    expect(c.lines).not.toBe(a.lines);
    expect(a.lines).toMatch(/^M[\d. L]+/);
  });

  it('під розмовою рівне тло: рельєфу в корені розмови немає (макет 04, W1)', () => {
    expect(read('src/components/messenger/MessengerRoot.tsx')).not.toMatch(/<TerrainLayer/);
  });
});

describe('рампа за сонцем замість підмішування', () => {
  it('theme.ts кличе applyRamp, підмішування тону в полотно прибране', () => {
    expect(read('src/services/theme.ts').match(/applyRamp\(/g)?.length).toBeGreaterThanOrEqual(4);
    expect(read('src/styles/identity.css')).not.toMatch(/--ph-sun-/);
  });
});

describe('лист: печатка, дорога, голос, мініатюра', () => {
  it('печатка — раз і лише для свіжого листа', () => {
    const now = Date.parse('2026-10-02T12:00:00Z');
    expect(sealOnce({ id: 'fresh', sentAt: '2026-10-02T11:59:58Z' }, now)).toBe(true);
    expect(sealOnce({ id: 'fresh', sentAt: '2026-10-02T11:59:58Z' }, now)).toBe(false);
    expect(sealOnce({ id: 'old', sentAt: '2026-10-02T11:00:00Z' }, now)).toBe(false);
  });

  it('дорога листа малюється лише з дорогою, яку назвав вузол', () => {
    const { container, rerender } = render(<RouteDiagram transport="relay" to="Оксана" />);
    expect(container.querySelector('[data-route="store"]')).not.toBeNull();
    expect(screen.getByText('через сховок')).toBeTruthy();
    rerender(<RouteDiagram transport="direct" to="Оксана" />);
    expect(container.querySelector('[data-route="direct"]')).not.toBeNull();
    rerender(<RouteDiagram transport={undefined} to="Оксана" />);
    expect(container.innerHTML).toBe('');
  });

  it('хвиля голосового: ≤ 40 смужок, 4…24 px, пік = 24', () => {
    const bars = voiceBars(Array.from({ length: 300 }, (_, i) => Math.abs(Math.sin(i / 7))), 40);
    expect(bars).toHaveLength(40);
    expect(Math.max(...bars)).toBe(24);
    expect(Math.min(...bars)).toBeGreaterThanOrEqual(4);
    expect(voiceBars([])).toEqual([]);
  });

  it('мікромініатюра: лише малий data:image, і вона доїжджає з тіла листа до медіа', () => {
    const ok = `data:image/jpeg;base64,${'A'.repeat(400)}`;
    expect(isMicroThumb(ok)).toBe(true);
    expect(isMicroThumb('https://evil.example/x.jpg')).toBe(false);
    expect(isMicroThumb('data:text/html;base64,AAAA')).toBe(false);
    expect(isMicroThumb(`data:image/jpeg;base64,${'A'.repeat(MICRO_MAX_CHARS)}`)).toBe(false);
    const body = { name: 'a.jpg', size: 1, mime: 'image/jpeg', sha256: 's', blob_id: 'b', key_hex: 'k', nonce_hex: 'n', width: 4, height: 3 };
    const row = (extra: object) =>
      ({ id: 'm', conversation_id: 'c', author_id: 'p', author_name: 'О', kind: 'image', body: JSON.stringify({ ...body, ...extra }), sent_at: '2026-10-02T12:00:00Z' }) as unknown as NodeMessage;
    expect(messageFromNode(row({ thumb: ok }), 'me', 'p').media?.thumb).toBe(ok);
    expect(messageFromNode(row({ thumb: 'javascript:alert(1)' }), 'me', 'p').media?.thumb).toBeUndefined();
  });

  it('відправник кладе мікромініатюру в тіло листа', () => {
    const store = read('src/stores/messengerStore.ts');
    expect(store).toMatch(/thumb: prepared\.micro/);
    expect(store.match(/\.\.\.\(media\.thumb \? \{ thumb: media\.thumb \} : \{\}\)/g)?.length).toBeGreaterThanOrEqual(3);
  });
});

describe('Nightpost', () => {
  it('знак у вебі — той самий шлях, що й «Естафета» в docs/brand', () => {
    const svg = readFileSync(join(REPO, 'docs/brand/assets/symbol/nightpost-symbol-24.svg'), 'utf8');
    expect(svg).toContain(`d="${PHANTOM_MARK_PATH}"`);
  });

  it('порожня розмова з Nightpost: підказки надсилають саме ці слова', () => {
    const send = vi.fn();
    const prev = useMessengerStore.getState().sendMessage;
    useMessengerStore.setState({ sendMessage: send } as never);
    const chat = { id: 'chat_phantom_assistant', title: 'Nightpost', type: 'phantom', messages: [], unreadCount: 0 } as unknown as Chat;
    const noop = () => {};
    render(
      <MemoryRouter>
        <ChatArea
          currentChat={chat}
          messages={[]}
          currentUserId="me"
          onOpenLocation={noop}
          onVotePoll={noop}
          onPayBillShare={noop}
          onAddReaction={noop}
          onReplyMessage={noop}
          selectedMessageIds={[]}
          onToggleSelectMessage={noop}
          isSelectionMode={false}
          isSearching={false}
          onCloseSearch={noop}
        />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('phantom-start')).toBeTruthy();
    expect(screen.getByTestId('phantom-chips').textContent).not.toMatch(/укрит|мереж|shelter|network/i);
    fireEvent.click(screen.getByText(PHANTOM_STARTERS[0]));
    expect(send).toHaveBeenCalledWith(PHANTOM_STARTERS[0]);
    useMessengerStore.setState({ sendMessage: prev } as never);
  });

  it('своя бульбашка без градієнта й намальованого хвоста; відповідь помічника — плоска бульбашка 16', () => {
    expect(IDENTITY).not.toMatch(/gradient\(/);
    expect(IDENTITY).not.toMatch(/bubble-contour-self-last::after/);
    expect(IDENTITY).toMatch(/\.msg-bubble-phantom \{[\s\S]*?border-radius: 16px/);
  });
});

describe('гаптика лише через міст', () => {
  it('кличе PhantomBridge.haptic, зливає ближчі за 80 мс, без мосту — тиша (не navigator.vibrate)', async () => {
    const { haptic } = await import('../utils/haptic');
    const w = window as unknown as { PhantomBridge?: unknown };
    const vib = vi.fn();
    Object.defineProperty(navigator, 'vibrate', { value: vib, configurable: true });
    w.PhantomBridge = undefined;
    expect(haptic('tap', 1000)).toBe(false);
    const call = vi.fn();
    w.PhantomBridge = { haptic: call };
    expect(haptic('success', 2000)).toBe(true);
    expect(haptic('tap', 2050)).toBe(true);
    expect(haptic('tap', 2080)).toBe(false);
    expect(haptic('tap', 2200)).toBe(true);
    expect(call.mock.calls.map((c) => c[0])).toEqual(['success', 'tap', 'tap']);
    expect(vib).not.toHaveBeenCalled();
    delete w.PhantomBridge;
  });

  it('T3: тап, тік і довгий дотик з різних джерел на відкритті чату — один тік', async () => {
    const { haptic } = await import('../utils/haptic');
    const w = window as unknown as { PhantomBridge?: unknown };
    const call = vi.fn();
    w.PhantomBridge = { haptic: call };
    expect(haptic('tap', 9000)).toBe(true);
    expect(haptic('tick', 9020)).toBe(false);
    expect(haptic('long', 9060)).toBe(false);
    expect(haptic('receive', 9090)).toBe(false);
    expect(haptic('success', 9095)).toBe(true);
    expect(call.mock.calls.map((c) => c[0])).toEqual(['tap', 'success']);
    delete w.PhantomBridge;
  });

  it('печатка і поріг свайпу справді кличуть гаптику (досяжність)', () => {
    expect(read('src/components/messenger/feed/feedParts.tsx')).toMatch(/if \(seal\) haptic\('success'\)/);
    expect(read('src/components/messenger/PhoneChatList.tsx')).toMatch(/SWIPE_OPEN_AT\)\) haptic\('tap'\)/);
  });
});

describe('заставка вебу — той самий знак і полотно v1.2', () => {
  it('index.html малює знак і стоїть на полотні токенів обох тем', () => {
    const html = read('index.html');
    expect(html).toContain(`d="${PHANTOM_MARK_PATH}"`);
    expect(html.toLowerCase()).toContain(`--boot-canvas: ${TOKENS.color.night.canvas.value.toLowerCase()};`);
    expect(html.toLowerCase()).toContain(`--boot-canvas: ${TOKENS.color.day.canvas.value.toLowerCase()};`);
    expect(read('public/phantom-icon.svg')).toContain(`d="${PHANTOM_MARK_PATH}"`);
  });
});
