import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, cleanup, waitFor, act, fireEvent } from '@testing-library/react';
import { intentsApi, INTENT_STATUS, type IntentRow } from '../services/intentsApi';
import { answerText, intentLine } from '../services/intentWords';
import { useIntentsStore, installIntentsHook } from '../stores/intentsStore';
import { IntentTrail } from '../components/messenger/IntentTrail';
import { IntentsList, MyIntents } from '../components/messenger/MyIntents';
import { messageFromNode, type NodeMessage } from '../services/messengerApi';

const row = (over: Partial<IntentRow> = {}): IntentRow => ({
  id: 'a1',
  op: 'ask',
  title: 'як зупинити кровотечу?',
  created_ms: 1_790_000_000_000,
  deadline_ms: 1_790_000_300_000,
  phase: 'SENT',
  tries: [{ lane: 'relay', at_ms: 1_790_000_000_000 }],
  relay_allowed: true,
  answer: null,
  ...over,
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useIntentsStore.setState({ rows: null });
});

describe('чесні слова про прохання (розд. 07 §2.3)', () => {
  it('називає смугу, а не «завантаження»', () => {
    expect(intentLine(row())).toBe('іде через ПК удома · сховок');
    const both = row({ tries: [{ lane: 'relay', at_ms: 1 }, { lane: 'sms', at_ms: 2 }] });
    expect(intentLine(both)).toBe('іде через ПК удома · сховок і SMS');
    expect(intentLine(row({ phase: 'AT_PC' }))).toBe('ПК удома отримав · готує відповідь');
    expect(intentLine(row({ phase: 'EXPIRED' }))).toContain('не встигло');
  });

  it('після SMS із щаблем 1 каже, що повна відповідь ще їде сховком', () => {
    const r = row({ phase: 'ANSWERED', answer: { rung: 1, status: 0, lane: 'sms', at_ms: 3, text: 'Тисніть рану' } });
    expect(intentLine(r)).toBe('відповів ПК удома · прийшло SMS · повна відповідь їде сховком');
    const full = row({ phase: 'ANSWERED', answer: { rung: 2, status: 0, lane: 'relay', at_ms: 4, text: 'x' } });
    expect(intentLine(full)).toBe('відповів ПК удома · прийшло сховком');
  });

  it('мовчазне джерело ніколи не стає «тривог немає»', () => {
    const silent = answerText({ rung: 1, status: INTENT_STATUS.SOURCE_SILENT, lane: 'sms', at_ms: 1 });
    expect(silent).toContain('Джерело мовчить');
    expect(silent).not.toMatch(/тривог немає/i);
    const ok = answerText({
      rung: 1, status: 0, lane: 'sms', at_ms: 1,
      alerts: { oblasts: ['kharkiv', 'sumy'], mine: true, since_min: 11, age_s: 20 },
    });
    expect(ok).toBe('Тривога: Харківська, Сумська · у вашій області — так, 11 хв · дані 20 с тому');
    expect(answerText({ rung: 1, status: INTENT_STATUS.TIMEOUT, lane: 'sms', at_ms: 1 })).toContain('думає довше');
  });
});

describe('IntentTrail під питанням у розмові з Nightpost', () => {
  it('показує смугу, а потім відповідь', async () => {
    useIntentsStore.setState({ rows: [row({ tries: [{ lane: 'relay', at_ms: 1 }, { lane: 'sms', at_ms: 2 }] })] });
    render(<IntentTrail intentId="a1" />);
    expect(screen.getByTestId('intent-line').textContent).toBe('іде через ПК удома · сховок і SMS');
    expect(screen.queryByTestId('intent-answer')).toBeNull();
    act(() => {
      useIntentsStore.setState({
        rows: [row({ phase: 'ANSWERED', answer: { rung: 2, status: 0, lane: 'relay', at_ms: 5, text: 'Притисніть рану.' } })],
      });
    });
    await waitFor(() => expect(screen.getByTestId('intent-answer').textContent).toBe('Притисніть рану.'));
  });

  it('невідомий id — нічого не малює', () => {
    useIntentsStore.setState({ rows: [row()] });
    const { container } = render(<IntentTrail intentId="zz" />);
    expect(container.innerHTML).toBe('');
  });

  it('подія мосту оновлює журнал', async () => {
    const list = vi.spyOn(intentsApi, 'list').mockResolvedValue({ intents: [row({ id: 'b2' })] });
    installIntentsHook();
    (globalThis as { __phantomIntentsChanged?: () => void }).__phantomIntentsChanged?.();
    await waitFor(() => expect(useIntentsStore.getState().rows?.[0]?.id).toBe('b2'));
    expect(list).toHaveBeenCalled();
  });
});

describe('Профіль › Nightpost: «Мої прохання»', () => {
  beforeEach(() => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}' };
  });

  it('порожній журнал — немає частини', async () => {
    vi.spyOn(intentsApi, 'list').mockResolvedValue({ intents: [] });
    const { container } = render(<MyIntents onOpen={() => {}} />);
    await waitFor(() => expect(useIntentsStore.getState().rows).toEqual([]));
    expect(container.innerHTML).toBe('');
  });

  it('міст не відповів — немає частини, а не вигаданий список', async () => {
    vi.spyOn(intentsApi, 'list').mockRejectedValue(new Error('no bridge'));
    const { container } = render(<MyIntents onOpen={() => {}} />);
    await waitFor(() => expect(intentsApi.list).toHaveBeenCalled());
    expect(container.innerHTML).toBe('');
  });

  it('рядок у «Я» — останнє прохання з чесним станом, дотик веде на «Без інтернету»', async () => {
    vi.spyOn(intentsApi, 'list').mockResolvedValue({
      intents: [row(), row({ id: 'b2', title: 'тривоги?', created_ms: 1_789_000_000_000 })],
    });
    const onOpen = vi.fn();
    render(<MyIntents onOpen={onOpen} />);
    const entry = await screen.findByTestId('me-intents');
    expect(entry.textContent).toContain('Мої прохання до ПК');
    expect(entry.textContent).toContain('як зупинити кровотечу? · іде через ПК удома · сховок');
    fireEvent.click(entry);
    expect(onOpen).toHaveBeenCalledTimes(1);
  });

  it('на «Без інтернету» — останні прохання повністю, новіші зверху', async () => {
    useIntentsStore.setState({ rows: [row({ id: 'b2', title: 'тривоги?', created_ms: 1_789_000_000_000 }), row()] });
    render(<IntentsList />);
    const items = screen.getByTestId('intents-list').querySelectorAll('li');
    expect(items).toHaveLength(2);
    expect(items[0].textContent).toContain('як зупинити кровотечу?');
    expect(screen.getAllByText('іде через ПК удома · сховок')).toHaveLength(2);
  });
});

describe('рядок мосту несе id прохання', () => {
  it('messageFromNode віддає intentId', () => {
    const node = {
      id: 'm1', conversation_id: 'chat_phantom_assistant', client_id: 'c', seq: 1, author_id: 'me', author_name: 'Я',
      kind: 'text', body: 'тривоги?', ciphertext: null, transport: null, sent_at: '2026-10-02T10:00:00Z',
      edited_at: null, deleted_at: null, intent_id: 'a1',
    } as NodeMessage;
    expect(messageFromNode(node, 'me').intentId).toBe('a1');
    expect(messageFromNode({ ...node, intent_id: undefined }, 'me').intentId).toBeUndefined();
  });
});

describe('«Сирена-маяк» словами', () => {
  it('свіже й перевірене — як «зараз»; старе — як «тоді»; неперевірене — не показуємо', () => {
    const base = { rung: 1, status: 0, lane: 'sms' as const, at_ms: 1 };
    expect(answerText({ ...base, siren: { verified: true, fresh: true, age_min: 2, oblasts: ['kharkiv'], observed_at: 1 } }))
      .toBe('Тривога: Харківська · перевірено підписом · 2 хв тому');
    const stale = answerText({ ...base, siren: { verified: true, fresh: false, age_min: 40, oblasts: [], observed_at: 1 } });
    expect(stale).toContain('застарів');
    expect(stale).not.toMatch(/^Тривог в областях немає/);
    const forged = answerText({ ...base, siren: { verified: false, fresh: false, age_min: null, oblasts: ['kharkiv'], observed_at: null } });
    expect(forged).toContain('не показуємо');
    expect(forged).not.toContain('Харківська');
  });
});
