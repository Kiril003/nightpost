/**
 * K5: «Місце наживо» в групі — це сеанс телефона (`/messenger/live/*`), а не
 * `watchPosition` у WebView. Сеанс живе, коли людина пішла з розмови: зупинка
 * лише рукою. Бульбашка відкриває нативну мапу, а не веб-маршрут, якого в
 * телефонній збірці немає.
 */
import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MessageComposer } from '../components/messenger/MessageComposer';
import { LiveLocationBubble } from '../components/messenger/LiveLocationBubble';
import { useMessengerStore } from '../stores/messengerStore';
import { tNow } from '../components/chat/tNow';
import { ME, installBridge, removeBridge, type Answer, type Call } from './meetupFixtures';

const props = {
  onSendMessage: () => {},
  onSendVoiceMessage: () => {},
  onOpenActions: () => {},
  onOpenScheduler: () => {},
  replyingTo: null,
  onCancelReply: () => {},
  editingMessage: null,
  onCancelEdit: () => {},
  onSaveEdit: () => {},
  selectedMessagesForQuote: [],
  onSynthesizeMultiQuote: () => {},
  onClearSelectedQuotes: () => {},
  chatId: 'grp',
};

const member = (id: string, name: string, isMe = false) =>
  ({ id, name, handle: '', avatar: '', role: 'member', isMe }) as never;
const MEMBERS = [member(ME, 'Я', true), member('p1', 'Ana'), member('p2', 'Petr')];

let calls: Call[];
let start: (c: Call) => Answer;
let sharing = false;
let watch: ReturnType<typeof vi.fn>;
let clearWatch: ReturnType<typeof vi.fn>;
let sendLive: ReturnType<typeof vi.fn>;
let prev: ReturnType<typeof useMessengerStore.getState>;
const flush = () => act(async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); });
const posts = (what: 'start' | 'stop') => calls.filter((c) => c.m === 'POST' && c.p === `/messenger/live/${what}`);

const renderComposer = (members = MEMBERS) => render(<MessageComposer {...props} wherePeer={null} chatMembers={members} />);
async function liveTile() {
  fireEvent.click(screen.getByTestId('composer-attach'));
  return screen.queryByTestId('send-live');
}
/** «Місце наживо» → строк: 15 хв / 1 год / 8 год. */
async function startLive(minutes = 60) {
  fireEvent.click((await liveTile())!);
  await flush();
  fireEvent.click(screen.getByTestId(`live-span-${minutes}`));
}

beforeEach(() => {
  prev = useMessengerStore.getState();
  sendLive = vi.fn(async () => {});
  useMessengerStore.setState({
    currentUser: { ...useMessengerStore.getState().currentUser, id: ME, name: 'Я' },
    chats: [{ id: 'grp', type: 'group', title: 'Friday', messages: [], unreadCount: 0, members: MEMBERS }] as never,
    activeChatId: 'grp',
    sendLiveLocation: sendLive as never,
  });
  sharing = false;
  start = () => ({ data: { started: true, reach: 'RELAY' } });
  calls = installBridge((c) => {
    if (c.p === '/messenger/live/start') return start(c);
    if (c.p === '/messenger/live/stop') return { data: { stopped: true } };
    if (c.p.startsWith('/messenger/live/status')) return { data: { sharing_active: sharing, peer_sharing: false } };
    return undefined;
  });
  clearWatch = vi.fn();
  watch = vi.fn((ok: PositionCallback) => {
    ok({ coords: { latitude: 50.45, longitude: 30.52, accuracy: 10 }, timestamp: Date.now() } as GeolocationPosition);
    return 7;
  });
  Object.defineProperty(navigator, 'geolocation', {
    configurable: true,
    value: { getCurrentPosition: () => {}, watchPosition: watch, clearWatch },
  });
});

afterEach(() => {
  cleanup();
  removeBridge();
  useMessengerStore.setState(prev, true);
});

describe('місце наживо — сеанс телефона, що переживає розмову', () => {
  it('«Місце наживо» відкриває сеанс телефона для кожного учасника, крім мене', async () => {
    renderComposer();
    await flush();
    await startLive();
    await flush();
    expect(posts('start').map((c) => c.b)).toEqual([
      { peer_id: 'p1', minutes: 60 },
      { peer_id: 'p2', minutes: 60 },
    ]);
    expect(watch).not.toHaveBeenCalled();
    expect(sendLive).not.toHaveBeenCalled();
  });

  it('пішов із розмови — сеанс не зупиняється', async () => {
    const view = renderComposer();
    await flush();
    await startLive();
    await flush();
    view.unmount();
    await flush();
    expect(posts('stop')).toEqual([]);
    expect(clearWatch).not.toHaveBeenCalled();
    expect(sendLive.mock.calls.some((c) => (c[0] as { revoked?: boolean } | undefined)?.revoked)).toBe(false);
  });

  it('повернувся — плитка знає, що сеанс живий, і зупиняє його лише дотиком', async () => {
    sharing = true;
    renderComposer();
    await flush();
    const tile = (await liveTile())!;
    expect(tile.textContent).toBe(tNow('messageComposer.liveStop'));
    fireEvent.click(tile);
    await flush();
    expect(posts('stop').map((c) => c.b)).toEqual([{ peer_id: 'p1' }, { peer_id: 'p2' }]);
  });

  it('нікому не дійде (немає ключа) — плитка лишається «почати», і людині сказано чому', async () => {
    start = () => ({ data: { started: true, reach: 'NO_KEY' } });
    renderComposer();
    await flush();
    await startLive();
    await flush();
    expect(screen.getByText(tNow('messageComposer.liveUnsupported'))).toBeTruthy();
    fireEvent.click(screen.getByTestId('composer-attach'));
    expect(screen.getByTestId('send-live').textContent).toBe(tNow('messageComposer.liveStart'));
  });

  it('строк обирає людина: 15 хв, 1 год або 8 год — і саме він їде телефону', async () => {
    renderComposer();
    await flush();
    fireEvent.click((await liveTile())!);
    await flush();
    expect(posts('start')).toEqual([]);
    expect(screen.getByTestId('live-span-15')).toBeTruthy();
    fireEvent.click(screen.getByTestId('live-span-480'));
    await flush();
    expect(posts('start').map((c) => c.b)).toEqual([
      { peer_id: 'p1', minutes: 480 },
      { peer_id: 'p2', minutes: 480 },
    ]);
  });

  it('у групі без інших учасників плитки немає', async () => {
    renderComposer([member(ME, 'Я', true)]);
    await flush();
    expect(await liveTile()).toBeNull();
  });
});

describe('бульбашка наживо відкриває мапу телефона', () => {
  const location = { sessionId: 's1', startedAtMs: Date.now(), expiresAtMs: Date.now() + 3_600_000, point: { lat: 50.45, lon: 30.52, atMs: Date.now() } };
  const renderBubble = () =>
    render(
      <MemoryRouter initialEntries={['/messenger']}>
        <Routes>
          <Route path="/messenger" element={<LiveLocationBubble location={location} />} />
          <Route path="*" element={<div data-testid="navigated" />} />
        </Routes>
      </MemoryRouter>,
    );

  it('на телефоні — PhantomBridge.openMap з точкою, без переходу маршрутом', () => {
    const openMap = vi.fn(() => true);
    Object.assign((globalThis as unknown as { PhantomBridge: object }).PhantomBridge, { openMap });
    renderBubble();
    fireEvent.click(screen.getByText(tNow('liveLocationBubble.openMap')));
    expect(openMap).toHaveBeenCalledTimes(1);
    expect(openMap.mock.calls[0].slice(0, 2)).toEqual([50.45, 30.52]);
    expect(screen.queryByTestId('navigated')).toBeNull();
  });

  it('довгий строк — у годинах, а не сотнях хвилин; минув — останнє відоме місце', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-19T12:00:20Z'));
    onTestFinished(() => {
      vi.useRealTimers();
    });
    const long = { ...location, expiresAtMs: Date.now() + (7 * 60 + 30) * 60_000 };
    const { unmount } = render(<MemoryRouter><LiveLocationBubble location={long} /></MemoryRouter>);
    expect(screen.getByText(/7 год 30 хв/)).toBeTruthy();
    unmount();
    const gone = { ...location, startedAtMs: Date.now() - 7_200_000, expiresAtMs: Date.now() - 60_000 };
    render(<MemoryRouter><LiveLocationBubble location={gone} /></MemoryRouter>);
    expect(screen.getByText(tNow('liveLocationBubble.ended'))).toBeTruthy();
  });

  it('без моста (ПК) — як і раніше, маршрут мапи', () => {
    removeBridge();
    renderBubble();
    fireEvent.click(screen.getByText(tNow('liveLocationBubble.openMap')));
    expect(screen.getByTestId('navigated')).toBeTruthy();
  });
});
