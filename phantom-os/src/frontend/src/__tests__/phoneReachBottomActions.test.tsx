/**
 * Нижні дії на 360×640 з textZoom 130%: головна кнопка не живе в області, що гортається,
 * і не має фіксованої висоти, яку ламає перенос слів.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { TicketThread } from '../components/support/HelpCentre';
import { MomentAudienceSheet } from '../components/messenger/MomentAudienceSheet';
import { AddOwnDeviceSheet } from '../components/messenger/AddOwnDevice';
import { ProfileTemplatePreview } from '../components/messenger/ProfileTemplatePreview';
import { useSupportStore, type SupportTicket } from '../stores/supportStore';

vi.mock('../components/messenger/QrScanner', () => ({
  QrScanner: ({ onUnavailable }: { onUnavailable?: () => void }) => (
    <button type="button" onClick={() => onUnavailable?.()}>камери нема</button>
  ),
}));

const src = (rel: string) => readFileSync(join(__dirname, '..', rel), 'utf8');
const inScroller = (el: Element) => el.closest('.overflow-y-auto') !== null && el.closest('[data-pinned-action]') === null;

afterEach(() => {
  cleanup();
  delete (globalThis as { PhantomBridge?: unknown }).PhantomBridge;
});

describe('нижні дії видно на малому екрані', () => {
  it('W1: поле відповіді в тікеті стоїть у footer, а не в області прокрутки', () => {
    const ticket: SupportTicket = {
      id: 't1', type: 'other', subject: 'Тікет', text: 'x', status: 'open', unread: false, createdAt: Date.now(),
      thread: Array.from({ length: 14 }, (_, i) => ({ id: `m${i}`, from: i % 2 ? 'support' : 'you', text: `Повідомлення ${i}`, at: Date.now() })),
    };
    useSupportStore.setState({ tickets: [ticket] });
    render(<TicketThread ticketId="t1" onClose={() => undefined} />);
    expect(inScroller(screen.getByTestId('ticket-reply-input'))).toBe(false);
    expect(inScroller(screen.getByTestId('ticket-reply-send'))).toBe(false);
  });

  it('W2: сітка пачки вкладень обмежена за висотою й гортається', () => {
    const s = src('components/messenger/MessageComposer.tsx');
    const m = s.match(/className="([^"]*)" data-testid="attach-batch-grid"/);
    expect(m?.[1]).toMatch(/max-h-\[\d+dvh\]/);
    expect(m?.[1]).toContain('overflow-y-auto');
  });

  it('W3: «Готово» вибору аудиторії поза списком, що гортається', () => {
    const people = Array.from({ length: 30 }, (_, i) => ({ id: `p${i}`, name: `Людина ${i}`, chatId: `c${i}` }));
    render(
      <MomentAudienceSheet
        rungs={[{ reach: 'people', key: 'moments.rungContacts' }]}
        reach="people"
        onReach={() => undefined}
        circles={[]}
        chosenCircles={[]}
        onToggleCircle={() => undefined}
        people={people}
        activePeopleIds={[]}
        onTogglePerson={() => undefined}
        count={null}
        ready={false}
        noOne={false}
        outerRings={[]}
        onLeaveOut={() => undefined}
        onDone={() => undefined}
      />,
    );
    expect(inScroller(document.querySelector('[data-moment-audience-done]')!)).toBe(false);
  });

  it('W4a: «Додати» на кроці цифр закріплена', () => {
    (globalThis as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{"ok":true,"status":200,"data":[]}' };
    render(<AddOwnDeviceSheet open onClose={() => undefined} />);
    fireEvent.click(screen.getByText('камери нема'));
    fireEvent.click(screen.getByText('Ввести цифри з екрана'));
    const input = screen.getByLabelText('Цифри з екрана компʼютера');
    const btn = input.parentElement!.querySelector('button')!;
    expect(btn.closest('[data-pinned-action]')).not.toBeNull();
  });

  it('W4b: «Створити» у перегляді шаблону закріплена', () => {
    render(<ProfileTemplatePreview kind="work" onBack={() => undefined} onCreate={() => undefined} />);
    expect(screen.getByTestId('profile-template-create').closest('[data-pinned-action]')).not.toBeNull();
  });

  it('W5: кнопки, де текст може переноситись, без фіксованого h-12', () => {
    for (const f of ['components/messenger/FormSheet.tsx', 'components/messenger/DeleteMessageModal.tsx', 'components/messenger/PeerProfileSheet.tsx', 'components/messenger/MeSections.tsx']) {
      expect(src(f)).not.toMatch(/h-12 min-h-0 rounded-(control|full)/);
    }
  });
});
