/**
 * msg-x 2 (власник 28.10: «смужка з фіксованим часом», «декілька вікон»): «Коли» — стопка вікон
 * без сталих годин і без системних полів; відкладені листи переносяться, правляться, повторюються
 * і чекають, «коли людина буде в мережі».
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ScheduledMessagesDrawer } from '../components/messenger/ScheduledMessagesDrawer';
import { quickPicks, nextRepeat, ceilStep } from '../components/messenger/when/whenModel';
import { useMessengerStore, scheduledFromDevice } from '../stores/messengerStore';
import { messengerApi, type NodeScheduledLetter } from '../services/messengerApi';
import type { Chat, ScheduledMessage } from '../types/messenger';

const OKSANA = { id: 'peer_ok', title: 'Оксана', type: 'dm', circle: 'all', avatar: '', unreadCount: 0, messages: [], peerNodeId: 'a1' } as unknown as Chat;
const SAVED = { id: 'chat_saved_notes', title: 'Збережене', type: 'saved', circle: 'all', avatar: '', unreadCount: 0, messages: [] } as unknown as Chat;
const WED_1840 = new Date(2026, 9, 28, 18, 40);
const noop = () => {};
const base = { isOpen: true, onClose: noop, onSendNow: noop, onCancel: noop };
const phone = () => ((window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {});
const slots = (d: Date) => quickPicks(d).map((p) => `${p.id} ${p.date} ${p.time}`);

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(WED_1840);
});
afterEach(() => {
  vi.useRealTimers();
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  cleanup();
  vi.restoreAllMocks();
});

describe('пропозиції рахуються від «зараз»', () => {
  it('середа 18:40: за годину, ввечері, завтра зранку, у суботу', () => {
    expect(slots(WED_1840)).toEqual(['inHour 2026-10-28 19:40', 'tonight 2026-10-28 21:00', 'tomorrowMorning 2026-10-29 09:00', 'saturday 2026-10-31 10:00']);
  });
  it('пізно ввечері «ввечері» зникає, а «за годину» переходить через північ', () => {
    expect(slots(new Date(2026, 9, 28, 23, 32))).toEqual(['inHour 2026-10-29 00:35', 'tomorrowMorning 2026-10-29 09:00', 'saturday 2026-10-31 10:00']);
  });
  it('у пʼятницю замість суботи (вона вже «завтра») — понеділок', () => {
    expect(slots(new Date(2026, 9, 30, 10, 0))).toContain('monday 2026-11-02 09:00');
    expect(slots(new Date(2026, 9, 30, 10, 0)).some((s) => s.startsWith('saturday'))).toBe(false);
  });
  it('крок 5 хвилин, ніколи минуле', () => {
    expect(ceilStep(new Date(2026, 9, 28, 18, 41, 10)).getMinutes()).toBe(45);
    for (let h = 0; h < 24; h++) for (const p of quickPicks(new Date(2026, 9, 28, h, 59))) expect(new Date(`${p.date}T${p.time}`).getTime()).toBeGreaterThan(new Date(2026, 9, 28, h, 59).getTime());
  });
  it('повтор — та сама місцева година; будні пропускають вихідні; 31-ше лишається 31-м', () => {
    expect(nextRepeat({ date: '2026-10-29', time: '09:00' }, 'weekly', new Date(2026, 9, 29, 9, 1).getTime())).toEqual({ date: '2026-11-05', time: '09:00' });
    expect(nextRepeat({ date: '2026-10-30', time: '09:00' }, 'weekdays', new Date(2026, 9, 30, 9, 1).getTime())).toEqual({ date: '2026-11-02', time: '09:00' });
    expect(nextRepeat({ date: '2026-10-31', time: '09:00' }, 'monthly', new Date(2026, 9, 31, 9, 1).getTime())).toEqual({ date: '2026-11-30', time: '09:00' });
    expect(nextRepeat({ date: '2026-10-31', time: '09:00' }, 'monthly', new Date(2026, 10, 30, 9, 1).getTime())).toEqual({ date: '2026-12-31', time: '09:00' });
  });
});

describe('аркуш «Надіслати пізніше»', () => {
  it('нема сталих чипів 19:00/09:00 і системних полів дати й часу', () => {
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={vi.fn()} initialText="привіт" />);
    expect(document.body.querySelector('input[type="date"],input[type="time"],input[type="datetime-local"]')).toBeNull();
    expect(screen.queryByRole('button', { name: /Сьогодні ввечері · 19:00/ })).toBeNull();
    expect(screen.getByText('Надіслати пізніше')).toBeTruthy();
  });

  it('один дотик до пропозиції — і лист запланований на справжні день і годину', () => {
    const onCreate = vi.fn();
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={onCreate} initialText="Так, давай" />);
    fireEvent.click(screen.getByTestId('when-quick-inHour'));
    expect(onCreate).toHaveBeenCalledWith({ chatId: OKSANA.id, text: 'Так, давай', date: '2026-10-28', time: '19:40' });
  });

  it('без тексту пропозиції вимкнені', () => {
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={vi.fn()} />);
    expect((screen.getByTestId('when-quick-inHour') as HTMLButtonElement).disabled).toBe(true);
  });

  it('«Коли Оксана буде в мережі» — лише розмова вдвох на телефоні', () => {
    const onCreate = vi.fn();
    const { unmount } = render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={onCreate} initialText="фото" />);
    expect(screen.queryByTestId('when-quick-online')).toBeNull();
    unmount();
    phone();
    const r2 = render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={onCreate} initialText="фото" />);
    fireEvent.click(screen.getByText('Коли Оксана буде в мережі'));
    expect(onCreate).toHaveBeenCalledWith({ chatId: OKSANA.id, text: 'фото', date: '', time: '', whenOnline: true });
    r2.unmount();
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={SAVED.id} chats={[SAVED]} onCreate={onCreate} initialText="x" />);
    expect(screen.queryByTestId('when-quick-online')).toBeNull();
  });

  it('дата → час → назад на одне вікно; минулі дні не обрати; текст живий', () => {
    const onCreate = vi.fn();
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={onCreate} initialText="у суботу" />);
    fireEvent.click(screen.getByTestId('when-pick'));
    expect(screen.getByTestId('when-month').textContent).toMatch(/^Жовтень 2026/);
    expect((document.querySelector('[data-day="2026-10-27"]') as HTMLButtonElement).disabled).toBe(true);
    expect(document.querySelector('[data-day="2026-10-28"]')!.getAttribute('aria-current')).toBe('date');
    expect(screen.getByRole('button', { name: 'Попередній місяць' }).hasAttribute('disabled')).toBe(true);
    fireEvent.click(document.querySelector('[data-day="2026-10-31"]')!);
    expect(screen.getByTestId('when-time')).toBeTruthy();
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '11' }));
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Хвилини' })).getByRole('option', { name: '30' }));
    fireEvent.click(screen.getAllByRole('button', { name: 'Назад' })[0]);
    expect(screen.getByTestId('when-date')).toBeTruthy();
    fireEvent.click(document.querySelector('[data-day="2026-10-31"]')!);
    fireEvent.click(screen.getByRole('button', { name: /^Надіслати · / }));
    expect(onCreate).toHaveBeenCalledWith({ chatId: OKSANA.id, text: 'у суботу', date: '2026-10-31', time: '11:30' });
  });

  it('сьогоднішня минула година — головна дія вимкнена й сказано чому', () => {
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={vi.fn()} initialText="x" />);
    fireEvent.click(screen.getByTestId('when-pick'));
    fireEvent.click(document.querySelector('[data-day="2026-10-28"]')!);
    fireEvent.click(within(screen.getByRole('listbox', { name: 'Години' })).getByRole('option', { name: '08' }));
    expect(screen.getByTestId('when-time-note').textContent).toBe('Цей час уже минув');
    expect((screen.getByRole('button', { name: /^Надіслати · / }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('повтор обирається у своєму вікні й іде разом із часом', () => {
    const onCreate = vi.fn();
    render(<ScheduledMessagesDrawer {...base} scheduledMessages={[]} currentChatId={OKSANA.id} chats={[OKSANA]} onCreate={onCreate} initialText="доброго ранку" />);
    fireEvent.click(screen.getByTestId('when-repeat'));
    fireEvent.click(screen.getByRole('radio', { name: /Щотижня/ }));
    expect(screen.getByTestId('when-repeat').textContent).toContain('Щотижня');
    fireEvent.click(screen.getByTestId('when-quick-tomorrowMorning'));
    expect(onCreate).toHaveBeenCalledWith({ chatId: OKSANA.id, text: 'доброго ранку', date: '2026-10-29', time: '09:00', repeat: 'weekly' });
  });
});

describe('список запланованих', () => {
  const row = (over: Partial<ScheduledMessage> = {}): ScheduledMessage =>
    ({ id: 's1', chatId: OKSANA.id, chatTitle: 'Оксана', text: 'Не забудь ключі', type: 'text', scheduledTime: '', scheduledDate: '2026-10-28', scheduledExactTime: '21:00', createdAt: '', ...over }) as ScheduledMessage;

  it('згруповано за днями; дотик до рядка — меню: зараз, змінити час, редагувати, видалити', () => {
    const onSendNow = vi.fn();
    const onMove = vi.fn();
    const onEditText = vi.fn();
    const onCancel = vi.fn();
    render(
      <ScheduledMessagesDrawer
        {...base}
        onSendNow={onSendNow}
        onCancel={onCancel}
        onMove={onMove}
        onEditText={onEditText}
        scheduledMessages={[row(), row({ id: 's2', scheduledDate: '2026-10-29', scheduledExactTime: '09:00', repeat: 'weekly', text: 'Доброго ранку' })]}
        currentChatId={OKSANA.id}
        chats={[OKSANA]}
      />,
    );
    expect(screen.getByText('Сьогодні')).toBeTruthy();
    expect(screen.getByText('Завтра')).toBeTruthy();
    expect(screen.getByLabelText('Щотижня')).toBeTruthy();
    fireEvent.click(screen.getAllByTestId('scheduled-row')[0]);
    const menu = screen.getByTestId('scheduled-menu');
    expect(within(menu).getAllByRole('button').map((b) => b.textContent)).toEqual(['Надіслати зараз', 'Змінити час', 'Редагувати текст', 'Видалити']);
    fireEvent.click(within(menu).getByText('Змінити час'));
    fireEvent.click(screen.getByTestId('when-quick-inHour'));
    expect(onMove).toHaveBeenCalledWith('s1', { kind: 'at', date: '2026-10-28', time: '19:40', repeat: '' });
    fireEvent.click(screen.getAllByTestId('scheduled-row')[1]);
    fireEvent.click(screen.getByText('Редагувати текст'));
    fireEvent.change(screen.getByLabelText('Текст листа'), { target: { value: 'Доброго ранку!' } });
    fireEvent.click(screen.getByRole('button', { name: 'Зберегти' }));
    expect(onEditText).toHaveBeenCalledWith('s2', 'Доброго ранку!');
    fireEvent.click(screen.getByText('Видалити'));
    expect(onCancel).toHaveBeenCalledWith('s2');
  });
});

describe('стор: апарат знає про перенос, правку, повтор і «в мережі»', () => {
  const letter = (over: Partial<NodeScheduledLetter> = {}): NodeScheduledLetter => ({
    id: 'L1', conversation_id: OKSANA.id, text: 'x', due_at_ms: new Date(2026, 9, 29, 9, 0).getTime(), created_at_ms: 0, quiet: false, failure: null, ...over,
  });
  beforeEach(() => {
    phone();
    useMessengerStore.setState({ chats: [OKSANA] as never, scheduledMessages: [scheduledFromDevice(letter(), [OKSANA])] });
    vi.spyOn(messengerApi, 'listScheduled').mockResolvedValue([letter()]);
  });

  it('новий з повтором і «коли буде в мережі»', () => {
    const create = vi.spyOn(messengerApi, 'createScheduled').mockResolvedValue(letter());
    useMessengerStore.getState().addScheduledMessage({ chatId: OKSANA.id, text: 'a', date: '2026-10-29', time: '09:00', repeat: 'weekly' });
    useMessengerStore.getState().addScheduledMessage({ chatId: OKSANA.id, text: 'b', date: '', time: '', whenOnline: true });
    expect(create.mock.calls[0][0]).toEqual({ conversation_id: OKSANA.id, text: 'a', due_at_ms: new Date(2026, 9, 29, 9, 0).getTime(), repeat: 'weekly' });
    expect(create.mock.calls[1][0]).toEqual({ conversation_id: OKSANA.id, text: 'b', due_at_ms: 0, when_online: true });
  });

  it('перенос і правка — PATCH на апарат', async () => {
    const patch = vi.spyOn(messengerApi, 'updateScheduled').mockResolvedValue(letter());
    useMessengerStore.getState().rescheduleScheduledMessage('L1', { date: '2026-10-31', time: '10:00', repeat: '' });
    useMessengerStore.getState().rescheduleScheduledMessage('L1', { whenOnline: true });
    useMessengerStore.getState().editScheduledText('L1', ' нове ');
    expect(patch.mock.calls).toEqual([
      ['L1', { due_at_ms: new Date(2026, 9, 31, 10, 0).getTime(), repeat: '' }],
      ['L1', { when_online: true }],
      ['L1', { text: 'нове' }],
    ]);
    await waitFor(() => expect(messengerApi.listScheduled).toHaveBeenCalled());
  });

  it('у минуле не переносить', () => {
    const patch = vi.spyOn(messengerApi, 'updateScheduled').mockResolvedValue(letter());
    useMessengerStore.getState().rescheduleScheduledMessage('L1', { date: '2026-10-28', time: '08:00' });
    expect(patch).not.toHaveBeenCalled();
  });

  it('лист апарата «коли буде в мережі» і з повтором читається чесно', () => {
    const s = scheduledFromDevice(letter({ when_online: true, due_at_ms: 0 }), [OKSANA]);
    expect([s.whenOnline, s.scheduledDate, s.scheduledExactTime]).toEqual([true, '', '']);
    expect(scheduledFromDevice(letter({ repeat: 'weekdays' }), [OKSANA]).repeat).toBe('weekdays');
    expect(scheduledFromDevice(letter({ repeat: 'hourly' }), [OKSANA]).repeat).toBeUndefined();
  });
});
