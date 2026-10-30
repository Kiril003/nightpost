/**
 * Картка знайденого в глобальному довіднику (ПК): ні коду ключа, ні відбитка
 * на видноті. Відбиток — лише після «Перевірити наживо».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import vector from './directory_v2_vector.json';
import { DirectorySearchPanel } from '../components/messenger/DirectorySearchPanel';

const SHORT_ID = /[0-9a-f]{4}·[0-9a-f]{4}/;

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('directory panel shows no key code', () => {
  it('a found record has no short id and a collapsed fingerprint', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () =>
        new Response(
          JSON.stringify({ invite: vector.invite.code, sig: vector.expected.publish_sig_b64url, exp: vector.exp }),
          { status: 200 },
        ),
      ),
    );
    render(<DirectorySearchPanel onUseInvite={() => {}} />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: vector.handle } });
    fireEvent.click(screen.getByRole('button'));

    const card = await screen.findByTestId('directory-found');
    expect(card.textContent ?? '').not.toMatch(SHORT_ID);
    expect(screen.queryByTestId('directory-fingerprint')).toBeNull();
    expect(screen.getByText(`@${vector.handle}`)).toBeTruthy();

    const toggle = screen.getByText('Перевірити наживо');
    expect(toggle.closest('button')?.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(toggle);
    expect(screen.getByTestId('directory-fingerprint')).toBeTruthy();
  });
});
