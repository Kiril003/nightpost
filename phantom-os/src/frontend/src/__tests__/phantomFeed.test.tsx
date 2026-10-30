/**
 * Профіль › PHANTOM: «Що я роблю» (незмінна стрічка) і «Стоп усе».
 * Немає відповіді вузла/мосту — немає розділу; ланцюжок розірвано — так і сказано.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, cleanup, waitFor, within } from '@testing-library/react';
import { messengerApi } from '../services/messengerApi';
import { phantomSelfApi } from '../services/phantomSelfApi';
import { useMessengerStore } from '../stores/messengerStore';
import { PhoneSettingsSheet } from '../components/messenger/PhoneSettingsSheet';
import { deviceApi, feedWords, missionWords, type Feed, type FeedEntry, type MissionLine } from '../services/deviceApi';

const NOW = Date.UTC(2026, 9, 2, 12, 0, 0);

const entry = (n: number, who: FeedEntry['who'], what: string, status: string, detail = ''): FeedEntry => ({
  n, who, what, status, detail, risk: '', hash: `h${n}`, at_iso: new Date(NOW - n * 60_000).toISOString(),
});

const FEED: Feed = {
  stopped: false,
  chain: { ok: true, count: 3, broken_at: null, why: '' },
  entries: [
    entry(3, 'mind', 'messenger.send', 'done'),
    entry(2, 'phone', 'phone.ring', 'done'),
    entry(1, 'mind', 'body.pc.type', 'stopped_by_owner', 'усе зупинено власником'),
  ],
};

const open = () =>
  render(<PhoneSettingsSheet isOpen onClose={() => {}} isSoundEnabled onToggleSound={() => {}} onExportAllData={() => {}} />);

let prevState: ReturnType<typeof useMessengerStore.getState>;

beforeEach(() => {
  prevState = useMessengerStore.getState();
  vi.spyOn(Date, 'now').mockReturnValue(NOW);
  vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'listBlocked').mockResolvedValue({ blocked: [] });
  vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
  vi.spyOn(messengerApi, 'privacy').mockResolvedValue({ read_receipts: true });
  vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);
  vi.spyOn(deviceApi, 'list').mockRejectedValue(new Error('немає'));
  useMessengerStore.setState({ currentUser: { ...prevState.currentUser, name: 'Кирило' }, chats: [] });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useMessengerStore.setState(prevState, true);
});

describe('Профіль › Nightpost', () => {
  it('рядки стрічки людськими словами: хто, що, чому', () => {
    expect(feedWords(FEED.entries[0])).toEqual({ what: 'Написав від вашого імені', state: 'Nightpost' });
    expect(feedWords(FEED.entries[1]).state).toBe('Телефон');
    const stopped = feedWords(FEED.entries[2]);
    expect(stopped.state).toContain('Зупинено');
    expect(stopped.state).toContain('усе зупинено власником');
  });

  it('вузол не відповів — розділу Nightpost немає', async () => {
    vi.spyOn(deviceApi, 'feed').mockRejectedValue(new Error('offline'));
    open();
    await waitFor(() => expect(deviceApi.feed).toHaveBeenCalled());
    expect(screen.queryByTestId('me-phantom')).toBeNull();
  });

  it('«Стоп усе» — перемикач, що кличе вузол, і показує його відповідь', async () => {
    vi.spyOn(deviceApi, 'feed').mockResolvedValue(FEED);
    const stop = vi.spyOn(deviceApi, 'stopAll').mockResolvedValue({ stopped: true });
    open();
    const sw = await screen.findByTestId('me-stop-all');
    expect(sw.getAttribute('role')).toBe('switch');
    expect(sw.getAttribute('aria-checked')).toBe('false');
    fireEvent.click(sw);
    await waitFor(() => expect(stop).toHaveBeenCalledWith(true));
    await waitFor(() => expect(screen.getByTestId('me-stop-all').getAttribute('aria-checked')).toBe('true'));
    expect(screen.getByTestId('me-stop-all').textContent).toContain('нічого не робить');
  });

  it('«Що я роблю» відкриває живу стрічку з цілим ланцюжком', async () => {
    vi.spyOn(deviceApi, 'feed').mockResolvedValue(FEED);
    open();
    fireEvent.click(await screen.findByTestId('me-feed'));
    const page = await screen.findByTestId('phantom-feed');
    expect(within(page).getByTestId('feed-chain').getAttribute('data-chain')).toBe('ok');
    expect(page.querySelectorAll('[data-feed-line]')).toHaveLength(3);
    expect(page.textContent).toContain('Написав від вашого імені');
  });

  it('телефон без ПК: чесне «не на звʼязку», а не порожня стрічка', async () => {
    vi.spyOn(deviceApi, 'feed').mockResolvedValue({ ...FEED, entries: [], offline: true });
    open();
    fireEvent.click(await screen.findByTestId('me-feed'));
    expect((await screen.findByTestId('feed-offline')).textContent).toContain('не на звʼязку');
    expect(screen.queryByText('Поки що Nightpost нічого не робив.')).toBeNull();
  });

  it('розірваний ланцюжок не ховається', async () => {
    vi.spyOn(deviceApi, 'feed').mockResolvedValue({
      ...FEED,
      chain: { ok: false, count: 1, broken_at: 2, why: 'ланцюжок розірвано' },
    });
    open();
    fireEvent.click(await screen.findByTestId('me-feed'));
    const chain = await screen.findByTestId('feed-chain');
    expect(chain.getAttribute('data-chain')).toBe('broken');
    expect(chain.textContent).toContain('записі 2');
  });

  it('доручення: ціль, скільки доведено, і дія назовні чекає вашого «так»', async () => {
    const asking: MissionLine = {
      id: 'm1', goal: 'Напиши Олі, що буду о сьомій', status: 'asking', tasks: 3, done: 1,
      ask: { id: 'a', tool: 'messenger.send', args: { text: 'Буду о сьомій' }, risk: 'outward' },
    };
    const running: MissionLine = { id: 'm2', goal: 'Познач кав’ярні на мапі', status: 'running', tasks: 5, done: 2, waiting: 'моделі зараз зайняті — чекаю' };
    const done: MissionLine = { id: 'm3', goal: 'Звіт про тиждень', status: 'done', tasks: 2, done: 2 };
    vi.spyOn(deviceApi, 'feed').mockResolvedValue({ ...FEED, missions: [asking, running, done] });
    const answer = vi.spyOn(deviceApi, 'answerMission').mockResolvedValue({ ...asking, status: 'running', ask: null });
    const control = vi.spyOn(deviceApi, 'mission').mockResolvedValue({ ...running, status: 'paused' });
    open();
    fireEvent.click(await screen.findByTestId('me-feed'));
    const list = await screen.findByTestId('feed-missions');
    const rows = list.querySelectorAll('[data-mission]');
    expect(Array.from(rows).map((r) => r.getAttribute('data-mission'))).toEqual(['asking', 'running', 'done']);
    expect(within(list).getByTestId('mission-ask').textContent).toContain('Написав від вашого імені «Буду о сьомій»');
    expect(rows[1].textContent).toContain('2 з 5 кроків доведено');
    expect(rows[1].textContent).toContain('моделі зараз зайняті');
    expect(rows[2].textContent).toContain('Виконано й доведено');
    expect(rows[2].querySelector('button')).toBeNull();

    fireEvent.click(within(rows[0] as HTMLElement).getByTestId('mission-yes'));
    await waitFor(() => expect(answer).toHaveBeenCalledWith('m1', true));
    fireEvent.click(within(rows[1] as HTMLElement).getByTestId('mission-pause'));
    await waitFor(() => expect(control).toHaveBeenCalledWith('m2', 'pause'));
  });

  it('на телефоні доручення видно, а «так» — на компʼютері', async () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {};
    try {
      const asking: MissionLine = {
        id: 'm1', goal: 'Напиши Олі', status: 'asking', tasks: 1, done: 0,
        ask: { id: 'a', tool: 'messenger.send', args: { text: 'Привіт' }, risk: 'outward' },
      };
      vi.spyOn(deviceApi, 'feed').mockResolvedValue({ ...FEED, missions: [asking] });
      open();
      fireEvent.click(await screen.findByTestId('me-feed'));
      const list = await screen.findByTestId('feed-missions');
      expect(within(list).queryByTestId('mission-yes')).toBeNull();
      expect(list.textContent).toContain('Відповісти можна на компʼютері');
    } finally {
      delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
    }
  });

  it('старий вузол без доручень — розділу доручень немає', async () => {
    vi.spyOn(deviceApi, 'feed').mockResolvedValue(FEED);
    open();
    fireEvent.click(await screen.findByTestId('me-feed'));
    await screen.findByTestId('phantom-feed');
    expect(screen.queryByTestId('feed-missions')).toBeNull();
    expect(missionWords({ id: 'x', goal: '', status: 'interrupted', tasks: 0, done: 0 }).state).toContain('Перервано');
  });

  it('рядки місії в стрічці — людськими словами', () => {
    expect(feedWords(entry(9, 'mind', 'mission.done', 'done')).what).toBe('Доручення виконано й доведено');
    expect(feedWords(entry(9, 'human', 'messenger.send', 'declined')).state).toContain('Людина сказала «ні»');
  });
});
