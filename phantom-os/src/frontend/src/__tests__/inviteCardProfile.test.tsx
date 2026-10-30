/**
 * S6 (веб): запрошення й QR говорять іменем активного профілю (SPEC-2810 §3):
 * ownName, інакше імʼя облікового запису; «Без імені» — без імені й без зміни.
 * Зміна імені в робочому профілі — це імʼя профілю, а не всього облікового запису.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { InviteCard, MyQrName } from '../components/messenger/InviteCard';
import { messengerApi } from '../services/messengerApi';
import { templateDefaults, useProfiles, type Profile, type ProfileKind } from '../stores/profilesStore';

vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn(async () => 'data:image/png;base64,AA==') } }));

const COMPACT = 'PH2:ZTc2:483778';
const P = (id: string, name: string, kind: ProfileKind, ownName = ''): Profile => ({ ...templateDefaults(kind, 1), id, name, ownName });
const personal = P('personal', 'Особисте', 'personal');
const biz = P('biz', 'Бізнес', 'business', 'Kavárna u Mostu');
const hidden = P('anon', 'Без імені', 'unnamed');

const initial = useProfiles.getState();

beforeEach(() => {
  (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = {
    call: (_m: string, path: string) =>
      path.split('?')[0] === '/messenger/identity'
        ? JSON.stringify({ ok: true, status: 200, data: { node_id: 'abc', bundle: { name: 'Nightpost-A52' }, compact: COMPACT, link: `phantom://invite/${COMPACT}` } })
        : JSON.stringify({ ok: false, status: 404, code: 'NOT_FOUND', detail: 'немає' }),
  };
});

afterEach(() => {
  delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  useProfiles.setState({ activeId: initial.activeId, profiles: initial.profiles, previousId: null });
  vi.restoreAllMocks();
});

const open = async (activeId: string) => {
  useProfiles.setState({ activeId, profiles: [personal, biz, hidden], previousId: null });
  render(<InviteCard />);
  return screen.findByTestId('invite-name');
};

describe('запрошення іменем активного профілю', () => {
  it('у «Бізнесі» — імʼя бізнесу, не імʼя облікового запису', async () => {
    const named = await open('biz');
    expect(named.textContent).toContain('Kavárna u Mostu');
    expect(named.textContent).not.toContain('Nightpost-A52');
  });

  it('профіль без власного імені — імʼя облікового запису', async () => {
    const named = await open('personal');
    expect(named.textContent).toContain('Nightpost-A52');
  });

  it('«Без імені» — без імені й без зміни', async () => {
    const named = await open('anon');
    expect(named.textContent).toContain('Без імені');
    expect(named.textContent).not.toContain('Nightpost-A52');
    fireEvent.click(named);
    expect(screen.queryByTestId('invite-name-input')).toBeNull();
  });

  it('зміна в «Бізнесі» — імʼя профілю, обліковий запис не чіпаємо', async () => {
    const rename = vi.spyOn(messengerApi, 'renameSelf');
    fireEvent.click(await open('biz'));
    const input = screen.getByTestId('invite-name-input') as HTMLInputElement;
    expect(input.value).toBe('Kavárna u Mostu');
    fireEvent.change(input, { target: { value: 'Kavárna Most' } });
    fireEvent.submit(input.closest('form')!);
    await waitFor(() => expect(useProfiles.getState().profiles.find((p) => p.id === 'biz')?.ownName).toBe('Kavárna Most'));
    expect(rename).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId('invite-name').textContent).toContain('Kavárna Most'));
  });

  it('імʼя під QR у «Новій розмові» — те саме правило', () => {
    useProfiles.setState({ activeId: 'biz', profiles: [personal, biz, hidden], previousId: null });
    const me = { node_id: 'abc', bundle: { name: 'Nightpost-A52' }, compact: COMPACT };
    const { unmount } = render(<MyQrName me={me} onRenamed={vi.fn()} />);
    expect(screen.getByTestId('my-qr-name').textContent).toContain('Kavárna u Mostu');
    unmount();
    useProfiles.setState({ activeId: 'anon' });
    render(<MyQrName me={me} onRenamed={vi.fn()} />);
    expect(screen.getByTestId('my-qr-name').textContent).toContain('Без імені');
    expect(screen.getByTestId('my-qr-name').textContent).not.toContain('Nightpost-A52');
  });
});

