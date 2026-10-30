/**
 * Каркас інфо-вигляду (L4 зріз 1): одна головна дія, ≤3 швидкі, рядки виду,
 * вкладки з числами, ⋮ і мале обличчя в смузі, коли велике виїхало.
 *
 * Червоніти вміє: приберіть зріз QUICK_MAX — падає «не більше трьох»; покажіть
 * вкладку з нулем — падає «порожня вкладка»; не ховайте назву в смузі — падає
 * «смуга показує назву, лише коли обличчя виїхало».
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { InfoView, visibleTabs, type InfoAction } from '../components/messenger/InfoView';
import { SharedPanel } from '../components/messenger/SharedSection';
import { SHARED_TABS } from '../services/peerShared';

const q = (key: string): InfoAction => ({ key, label: key, icon: <i />, onClick: vi.fn(), testId: `q-${key}` });

const TABS = [
  { key: 'mem', label: 'Учасники', count: 6 },
  { key: 'media', label: 'Медіа', count: 48 },
  { key: 'voice', label: 'Голосові', count: 0 },
];

describe('InfoView', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('обличчя, одна головна дія і не більше трьох швидких', () => {
    const invite = vi.fn();
    render(
      <InfoView
        onBack={vi.fn()}
        avatar={<span />}
        title="Скелі"
        kind="Група · 6 учасників"
        description="Скелі по неділях"
        primary={{ key: 'invite', label: 'Запросити', icon: <i />, onClick: invite }}
        quick={[q('call'), q('mute'), q('search'), q('extra')]}
      />,
    );
    expect(screen.getByTestId('info-title').textContent).toBe('Скелі');
    expect(screen.getByTestId('info-kind').textContent).toBe('Група · 6 учасників');
    expect(screen.getByTestId('info-description').textContent).toBe('Скелі по неділях');
    fireEvent.click(screen.getByTestId('info-primary'));
    expect(invite).toHaveBeenCalledOnce();
    expect(screen.getByTestId('q-search')).toBeTruthy();
    expect(screen.queryByTestId('q-extra')).toBeNull();
  });

  it('порожня вкладка схована, доки її не обрали; вибір іде нагору', () => {
    expect(visibleTabs(TABS, 'mem').map((t) => t.key)).toEqual(['mem', 'media']);
    expect(visibleTabs(TABS, 'voice').map((t) => t.key)).toEqual(['mem', 'media', 'voice']);
    const onTab = vi.fn();
    render(
      <InfoView onBack={vi.fn()} avatar={<span />} title="Скелі" kind="Група" tabs={TABS} tab="mem" onTab={onTab}>
        <p>склад</p>
      </InfoView>,
    );
    expect(screen.queryByTestId('info-tab-voice')).toBeNull();
    expect(screen.getByTestId('info-tab-media').textContent).toBe('Медіа48');
    expect(screen.getByTestId('info-panel-mem').textContent).toBe('склад');
    fireEvent.click(screen.getByTestId('info-tab-media'));
    expect(onTab).toHaveBeenCalledWith('media');
  });

  it('рядки виду: стан, лічильник, перемикач', () => {
    const open = vi.fn();
    const flip = vi.fn();
    render(
      <InfoView
        onBack={vi.fn()}
        avatar={<span />}
        title="Скелі"
        kind="Група"
        rows={[
          { key: 'link', icon: <i />, label: 'Посилання-запрошення', aside: 'Активне', onClick: open, testId: 'r-link' },
          { key: 'req', icon: <i />, label: 'Запити на вступ', count: 1, onClick: open, testId: 'r-req' },
          { key: 'ask', icon: <i />, label: 'Вступ за запитом', toggle: { on: true, onChange: flip }, testId: 'r-ask' },
        ]}
      />,
    );
    expect(screen.getByTestId('r-link').textContent).toContain('Активне');
    expect(screen.getByTestId('r-req').textContent).toContain('1');
    fireEvent.click(screen.getByTestId('r-link'));
    expect(open).toHaveBeenCalledOnce();
    const sw = screen.getByTestId('r-ask');
    expect(sw.getAttribute('aria-checked')).toBe('true');
    fireEvent.click(sw);
    expect(flip).toHaveBeenCalledWith(false);
  });

  it('⋮ відкриває меню і закривається після вибору; без пунктів ⋮ немає', () => {
    const leave = vi.fn();
    const { rerender } = render(<InfoView onBack={vi.fn()} avatar={<span />} title="Скелі" kind="Група" />);
    expect(screen.queryByTestId('info-more')).toBeNull();
    rerender(<InfoView onBack={vi.fn()} avatar={<span />} title="Скелі" kind="Група" menu={[{ key: 'leave', label: 'Вийти', danger: true, onClick: leave }]} />);
    fireEvent.click(screen.getByTestId('info-more'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Вийти' }));
    expect(leave).toHaveBeenCalledOnce();
    expect(screen.queryByTestId('info-menu')).toBeNull();
  });

  it('смуга показує назву, лише коли обличчя виїхало', () => {
    let fire: (hit: boolean) => void = () => {};
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(cb: (e: { isIntersecting: boolean }[]) => void) {
          fire = (hit) => cb([{ isIntersecting: hit }]);
        }
        observe() {}
        disconnect() {}
      },
    );
    render(<InfoView onBack={vi.fn()} avatar={<span />} title="Скелі" kind="Група" />);
    const id = screen.getByTestId('info-bar-identity');
    expect(id.getAttribute('aria-hidden')).toBe('true');
    act(() => fire(false));
    expect(id.getAttribute('aria-hidden')).toBe('false');
    act(() => fire(true));
    expect(id.getAttribute('aria-hidden')).toBe('true');
  });

  it('вкладка «Спільного» без нічого — чесний порожній рядок, не сітка', () => {
    const shared = { media: [], files: [], voice: [], links: [], places: [], payments: [], meetups: [] };
    render(<SharedPanel shared={shared} tab="voice" whoSent={() => ''} onJump={vi.fn()} onOpenImage={vi.fn()} />);
    expect(screen.getByTestId('shared-empty-voice').textContent).toBe(SHARED_TABS.find((x) => x.key === 'voice')!.empty);
  });
});
