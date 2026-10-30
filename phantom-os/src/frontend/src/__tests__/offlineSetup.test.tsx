import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, cleanup, waitFor, fireEvent } from '@testing-library/react';
import { intentsApi, type IntentSetup } from '../services/intentsApi';
import { useIntentsStore } from '../stores/intentsStore';
import { OfflineEntry, OfflineSetup } from '../components/messenger/OfflineSetup';

const base: IntentSetup = {
  sms_build: true, permitted: false, pc_paired: true, gateway_number: '', personal: false, quiet: false,
  home_gateway: false, allowed: [],
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  useIntentsStore.setState({ rows: null });
});

describe('«Без інтернету»', () => {
  it('магазинна збірка — чесно: SMS-шляху тут немає, дозволів не просимо', async () => {
    vi.spyOn(intentsApi, 'setup').mockResolvedValue({ ...base, sms_build: false });
    const permit = vi.spyOn(intentsApi, 'permit');
    render(<OfflineSetup />);
    await waitFor(() => expect(screen.getByTestId('offline-store').textContent).toContain('SMS-шлях доступний у версії з сайту'));
    expect(screen.queryByText('Дозволити SMS')).toBeNull();
    expect(permit).not.toHaveBeenCalled();
    expect(screen.getByTestId('offline-test')).toBeTruthy();
  });

  it('спершу людське пояснення, і лише потім — кнопка дозволу', async () => {
    vi.spyOn(intentsApi, 'setup').mockResolvedValue(base);
    const permit = vi.spyOn(intentsApi, 'permit').mockResolvedValue({ asked: true });
    render(<OfflineSetup />);
    const button = await screen.findByText('Дозволити SMS');
    const why = screen.getByText(/Інших SMS застосунок не читає/);
    expect(why.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByLabelText('Номер домашнього шлюзу')).toBeNull();
    fireEvent.click(button);
    expect(permit).toHaveBeenCalledTimes(1);
  });

  it('з дозволами — номер шлюзу, режим домашнього шлюзу й чесний лічильник', async () => {
    const permitted = { ...base, permitted: true, home_gateway: true, allowed: ['+380671234567'],
      ledger: { received: 4, forwarded: 4, sms_sent: 3, refused: 1, cap: 40 } };
    vi.spyOn(intentsApi, 'setup').mockResolvedValue(permitted);
    const save = vi.spyOn(intentsApi, 'saveSetup').mockResolvedValue({ ...permitted, gateway_number: '+380501112233' });
    render(<OfflineSetup />);
    const input = await screen.findByLabelText('Номер домашнього шлюзу');
    fireEvent.change(input, { target: { value: '+380501112233' } });
    fireEvent.click(screen.getByText('Зберегти'));
    await waitFor(() => expect(save).toHaveBeenCalledWith({ gateway_number: '+380501112233' }));
    expect(screen.getByText('прохань 4 · SMS 3 з 40 · відмов 1')).toBeTruthy();
    fireEvent.click(screen.getByRole('switch', { name: 'Тиха людина' }));
    await waitFor(() => expect(save).toHaveBeenCalledWith({ quiet: true }));
  });

  it('перевірка — одне справжнє прохання, і видно, яким шляхом прийшла відповідь', async () => {
    vi.spyOn(intentsApi, 'setup').mockResolvedValue({ ...base, sms_build: false });
    vi.spyOn(intentsApi, 'test').mockResolvedValue({ id: 't1' });
    vi.spyOn(intentsApi, 'list').mockResolvedValue({
      intents: [{
        id: 't1', op: 'ask', title: 'Перевірка', created_ms: 1, deadline_ms: 2, phase: 'ANSWERED',
        tries: [{ lane: 'relay', at_ms: 1 }], relay_allowed: true,
        answer: { rung: 2, status: 0, lane: 'relay', at_ms: 2, text: 'так' },
      }],
    });
    render(<OfflineSetup />);
    fireEvent.click(await screen.findByTestId('offline-test'));
    await waitFor(() => expect(screen.getByTestId('intent-line').textContent).toBe('відповів ПК удома · прийшло сховком'));
    expect(screen.getByTestId('intent-answer').textContent).toBe('так');
  });

  it('рядок у «Я»: немає мосту — немає рядка; є — чесний підпис шляху', async () => {
    vi.spyOn(intentsApi, 'setup').mockRejectedValueOnce(new Error('no bridge'));
    const { container } = render(<OfflineEntry onOpen={() => {}} />);
    await waitFor(() => expect(intentsApi.setup).toHaveBeenCalled());
    expect(container.innerHTML).toBe('');
    cleanup();
    vi.spyOn(intentsApi, 'setup').mockResolvedValue({ ...base, permitted: true, gateway_number: '+380671234567' });
    render(<OfflineEntry onOpen={() => {}} />);
    expect(await screen.findByText('через ПК удома · сховок і SMS')).toBeTruthy();
  });
});
