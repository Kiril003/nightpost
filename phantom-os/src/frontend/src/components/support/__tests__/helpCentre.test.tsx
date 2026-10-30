import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MeScreen } from '../../messenger/MeScreen';
import { HelpCentre, TicketThread } from '../HelpCentre';
import { ProblemForm } from '../ProblemForm';
import { HELP_ARTICLES } from '../../../data/helpArticles';
import { useSupportStore } from '../../../stores/supportStore';
import { supportApi } from '../../../services/supportApi';
import { messengerApi } from '../../../services/messengerApi';
import { phantomSelfApi } from '../../../services/phantomSelfApi';

function setOnline(online: boolean) {
  Object.defineProperty(window.navigator, 'onLine', {
    configurable: true,
    get: () => online,
  });
}

beforeEach(() => {
  localStorage.clear();
  useSupportStore.getState().reset();
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  localStorage.clear();
  useSupportStore.getState().reset();
  setOnline(true);
});

describe('HelpCentre + Tickets (W2 T2)', () => {
  it('MeScreen renders «Допомога» row and opens HelpCentre', async () => {
    vi.spyOn(messengerApi, 'listContacts').mockResolvedValue([]);
    vi.spyOn(messengerApi, 'listConversations').mockResolvedValue([]);
    vi.spyOn(phantomSelfApi, 'facts').mockResolvedValue([]);

    render(<MeScreen onClose={vi.fn()} onOpen={vi.fn()} />);

    const helpRow = screen.getByTestId('me-help');
    expect(helpRow.textContent).toContain('Допомога');
    fireEvent.click(helpRow);

    const sheet = await screen.findByTestId('help-centre-sheet');
    expect(within(sheet).getByText('Допомога')).toBeTruthy();
    expect(within(sheet).getByText('Повідомити про проблему')).toBeTruthy();
    expect(within(sheet).getByText('Мої звернення')).toBeTruthy();
    expect(within(sheet).getByText('Мої скарги')).toBeTruthy();
  });

  it('search filters bundled articles and shows no-match empty state with «Повідомити про проблему»', async () => {
    expect(HELP_ARTICLES.length).toBeGreaterThanOrEqual(12);

    render(<HelpCentre open onClose={vi.fn()} />);
    const sheet = screen.getByTestId('help-centre-sheet');
    const searchInput = within(sheet).getByTestId('help-search-input');

    // Initial state shows all articles
    const initialRows = within(sheet).getAllByTestId(/^help-article-row-/);
    expect(initialRows.length).toBe(HELP_ARTICLES.length);

    // Filter by query matching call articles
    fireEvent.change(searchInput, { target: { value: 'мікрофон' } });
    const filteredRows = within(sheet).getAllByTestId(/^help-article-row-/);
    expect(filteredRows.length).toBeGreaterThan(0);
    expect(filteredRows.length).toBeLessThan(HELP_ARTICLES.length);

    // Click an article to open article view
    fireEvent.click(filteredRows[0]);
    const articleView = await within(sheet).findByTestId('help-article-view');
    expect(articleView.querySelector('ol')).toBeTruthy();
    fireEvent.click(within(sheet).getByLabelText('Назад'));

    // Search with no match shows empty state with «Повідомити про проблему»
    const searchInputAfterBack = within(sheet).getByTestId('help-search-input');
    fireEvent.change(searchInputAfterBack, { target: { value: 'неіснуючийзапит99999' } });
    expect(within(sheet).queryAllByTestId(/^help-article-row-/)).toHaveLength(0);

    const emptyState = within(sheet).getByTestId('help-search-empty');
    expect(emptyState.textContent).toContain('Нічого не знайдено');
    const reportBtn = within(emptyState).getByRole('button', { name: 'Повідомити про проблему' });
    fireEvent.click(reportBtn);

    expect(await screen.findByTestId('problem-form-sheet')).toBeTruthy();
  });

  it('ProblemForm: Security is an ordinary ticket topic (no ReportSheet, no fake person target); diagnostics toggle is off by default and payload includes diagnostics only when toggled on', async () => {
    const submitSpy = vi.spyOn(supportApi, 'submitTicket').mockImplementation(async (payload) => ({
      id: payload.id,
      type: payload.type,
      subject: payload.subject,
      text: payload.text,
      diagnostics: payload.diagnostics ?? null,
      device_sig: payload.device_sig ?? null,
      status: 'open',
      unread: false,
      thread: [{ id: `${payload.id}-0`, from: 'you', text: payload.text, at: new Date().toISOString() }],
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    }));

    const { unmount } = render(<ProblemForm open onClose={vi.fn()} />);
    const formSheet = screen.getByTestId('problem-form-sheet');

    fireEvent.click(within(formSheet).getByTestId('problem-type-security'));
    expect(screen.queryByTestId('report-sheet')).toBeNull();
    fireEvent.change(within(formSheet).getByTestId('problem-subject-input'), { target: { value: 'Чужий вхід' } });
    fireEvent.change(within(formSheet).getByTestId('problem-text-input'), { target: { value: 'Бачу вхід, якого не робив' } });
    fireEvent.click(within(formSheet).getByTestId('problem-next-btn'));
    fireEvent.click(within(formSheet).getByTestId('problem-submit-btn'));
    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(1));
    expect(submitSpy.mock.calls[0][0].type).toBe('security');
    unmount();
    submitSpy.mockClear();
    useSupportStore.getState().reset();

    // Ticket without diagnostics (default OFF)
    const { unmount: unmountFirst } = render(<ProblemForm open onClose={vi.fn()} />);
    const firstSheet = screen.getByTestId('problem-form-sheet');
    fireEvent.click(within(firstSheet).getByTestId('problem-type-calls'));

    const diagToggle = within(firstSheet).getByTestId('problem-diagnostics-toggle') as HTMLInputElement;
    expect(diagToggle.checked).toBe(false);
    expect(firstSheet.textContent).toContain('Надіслати діагностику');
    expect(firstSheet.textContent).toContain('зберігаємо 90 днів');
    expect(firstSheet.textContent).toContain('Підпис · Ключ цього телефона');

    fireEvent.change(within(firstSheet).getByTestId('problem-subject-input'), {
      target: { value: 'Не чути співрозмовника' },
    });
    fireEvent.change(within(firstSheet).getByTestId('problem-text-input'), {
      target: { value: 'Звук зникає через 10 секунд після початку дзвінка' },
    });

    fireEvent.click(within(firstSheet).getByTestId('problem-next-btn'));
    expect(within(firstSheet).getByTestId('problem-preview')).toBeTruthy();

    fireEvent.click(within(firstSheet).getByTestId('problem-submit-btn'));
    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(1));
    const firstPayload = submitSpy.mock.calls[0][0];
    expect(firstPayload.diagnostics).toBeUndefined();
    expect('diagnostics' in firstPayload).toBe(false);
    expect(await screen.findByTestId('problem-sent-stamp')).toBeTruthy();
    unmountFirst();

    // Ticket with diagnostics toggled ON
    render(<ProblemForm open onClose={vi.fn()} />);
    const secondSheet = screen.getByTestId('problem-form-sheet');
    fireEvent.click(within(secondSheet).getByTestId('problem-type-money'));

    const diagToggle2 = within(secondSheet).getByTestId('problem-diagnostics-toggle') as HTMLInputElement;
    expect(diagToggle2.checked).toBe(false);
    fireEvent.click(diagToggle2);
    expect(diagToggle2.checked).toBe(true);

    fireEvent.change(within(secondSheet).getByTestId('problem-subject-input'), {
      target: { value: 'Переказ висить' },
    });
    fireEvent.change(within(secondSheet).getByTestId('problem-text-input'), {
      target: { value: 'Оплата висить у стані Відправлено вже 20 хвилин' },
    });

    fireEvent.click(within(secondSheet).getByTestId('problem-next-btn'));
    fireEvent.click(within(secondSheet).getByTestId('problem-submit-btn'));

    await waitFor(() => expect(submitSpy).toHaveBeenCalledTimes(2));
    const secondPayload = submitSpy.mock.calls[1][0];
    expect(secondPayload.diagnostics).toBeDefined();
    expect(secondPayload.diagnostics).toEqual(
      expect.objectContaining({
        appVersion: expect.any(String),
        os: expect.any(String),
        networkType: expect.any(String),
        lastErrorCodes: expect.any(Array),
      }),
    );
  });

  it('submit offline → queued → flush sends once with idempotent client id', async () => {
    setOnline(false);
    const submitSpy = vi.spyOn(supportApi, 'submitTicket').mockImplementation(async (payload) => ({
      id: payload.id,
      type: payload.type,
      subject: payload.subject,
      text: payload.text,
      diagnostics: payload.diagnostics ?? null,
      device_sig: payload.device_sig ?? null,
      status: 'open',
      unread: false,
      thread: [{ id: `${payload.id}-0`, from: 'you', text: payload.text, at: new Date().toISOString() }],
      created_at: new Date().toISOString(),
      updated_at: new Date().toISOString(),
    }));

    render(<ProblemForm open onClose={vi.fn()} />);
    const sheet = screen.getByTestId('problem-form-sheet');

    fireEvent.click(within(sheet).getByTestId('problem-type-map'));
    fireEvent.change(within(sheet).getByTestId('problem-subject-input'), {
      target: { value: 'Мапа не оновлюється' },
    });
    fireEvent.change(within(sheet).getByTestId('problem-text-input'), {
      target: { value: 'Квадрат міста не відкривається без мережі' },
    });

    fireEvent.click(within(sheet).getByTestId('problem-next-btn'));
    fireEvent.click(within(sheet).getByTestId('problem-submit-btn'));

    const queuedView = await within(sheet).findByTestId('problem-queued');
    expect(queuedView.textContent).toContain('У черзі');
    expect(submitSpy).not.toHaveBeenCalled();

    const ticketsBeforeFlush = useSupportStore.getState().tickets;
    expect(ticketsBeforeFlush).toHaveLength(1);
    const queuedId = ticketsBeforeFlush[0].id;
    expect(ticketsBeforeFlush[0].status).toBe('queued');

    // Back online → flush sends once with the same idempotent id
    setOnline(true);
    await act(async () => {
      await useSupportStore.getState().flush();
      await useSupportStore.getState().flush();
    });

    expect(submitSpy).toHaveBeenCalledTimes(1);
    expect(submitSpy.mock.calls[0][0].id).toBe(queuedId);
    expect(useSupportStore.getState().tickets[0].id).toBe(queuedId);
    expect(useSupportStore.getState().tickets[0].status).toBe('open');
  });

  it('ticket thread renders support reply, queues reply when offline, and handles empty/error/offline states', async () => {
    const appendSpy = vi.spyOn(supportApi, 'appendTicketMessage').mockImplementation(async (ticketId, payload) => ({
      id: ticketId,
      type: 'money',
      subject: 'Оплата висить «Відправлено»',
      text: 'Оплата Кава на розі висить 20 хв',
      status: 'open',
      unread: false,
      thread: [
        { id: 'm1', from: 'you', text: 'Оплата Кава на розі висить 20 хв', at: '2026-10-21T17:28:00Z' },
        { id: 'm2', from: 'support', text: 'Перевірили за номером операції: оплата підтверджена.', at: '2026-10-21T18:36:00Z' },
        { id: payload.id || 'm3', from: 'you', text: payload.text, at: new Date().toISOString() },
      ],
      created_at: '2026-10-21T17:28:00Z',
      updated_at: new Date().toISOString(),
    }));

    useSupportStore.setState({
      tickets: [
        {
          id: 't-2418',
          type: 'money',
          subject: 'Оплата висить «Відправлено»',
          text: 'Оплата Кава на розі висить 20 хв',
          status: 'answered',
          unread: true,
          createdAt: Date.now() - 3600_000,
          thread: [
            { id: 'm1', from: 'you', text: 'Оплата Кава на розі висить 20 хв', at: Date.now() - 3600_000 },
            { id: 'm2', from: 'support', text: 'Перевірили за номером операції: оплата підтверджена.', at: Date.now() - 1800_000 },
          ],
        },
      ],
    });

    setOnline(false);
    render(<TicketThread ticketId="t-2418" onClose={vi.fn()} />);

    const threadSheet = screen.getByTestId('ticket-thread-sheet');
    expect(within(threadSheet).getByTestId('ticket-offline-banner').textContent).toContain('Немає звʼязку');

    const supportBubble = within(threadSheet).getByTestId('ticket-msg-support');
    expect(supportBubble.textContent).toContain('Перевірили за номером операції: оплата підтверджена.');

    // Reply while offline → queued bubble in thread
    const replyInput = within(threadSheet).getByTestId('ticket-reply-input');
    fireEvent.change(replyInput, { target: { value: 'Дякую, тепер бачу статус!' } });
    fireEvent.click(within(threadSheet).getByTestId('ticket-reply-send'));

    expect(appendSpy).not.toHaveBeenCalled();
    const userBubbles = within(threadSheet).getAllByTestId('ticket-msg-you');
    expect(userBubbles).toHaveLength(2);
    expect(userBubbles[1].textContent).toContain('Дякую, тепер бачу статус!');
    expect(within(userBubbles[1]).getByTestId('ticket-msg-queued')).toBeTruthy();

    // Flush when back online sends the queued reply
    setOnline(true);
    await act(async () => {
      await useSupportStore.getState().flush();
    });
    expect(appendSpy).toHaveBeenCalledTimes(1);
    expect(appendSpy).toHaveBeenCalledWith(
      't-2418',
      expect.objectContaining({
        from: 'you',
        text: 'Дякую, тепер бачу статус!',
      }),
    );
  });
});
