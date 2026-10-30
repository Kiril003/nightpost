/**
 * Перші дві хвилини тестера (polish1, 18.10): що видно на 390 у День і Ніч.
 *  1. Типова назва ПК з рідного боку («My computer») — мовою інтерфейсу; власну назву не чіпаємо.
 *  2. Мій пристрій у теці — знак пристрою, а не літера, як у людини.
 *  3. Рядки «Додати свій пристрій» / «Увійти на сайті» у теці тримають ритм рядка списку (72).
 *  4. Порожній аркуш «Сьогодні» не розтягується на весь екран.
 *  5. Плитки «Я»: назва стоїть на одній лінії, є підпис чи ні.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, fireEvent } from '@testing-library/react';
import { PhoneChatList } from '../components/messenger/PhoneChatList';
import { displayTitle } from '../components/messenger/chatListFormat';
import { deviceName } from '../services/deviceApi';
import { DaySheet } from '../components/messenger/today/DaySheet';
import { MeTile } from '../components/messenger/MeRow';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';
import { Lock } from 'lucide-react';
import type { Chat, UserProfile } from '../types/messenger';

const setLocale = (l: 'uk' | 'en' | 'cs') =>
  useSettingsStore.setState((s) => ({ values: { ...s.values, [LANGUAGE_SETTING_KEY]: l } }));

const USER = { id: 'me', name: 'Кирило', avatar: '' } as unknown as UserProfile;
const chat = (id: string, title: string, extra: Partial<Chat> = {}): Chat =>
  ({ id, title, type: 'dm', circle: 'all', avatar: '', unreadCount: 0, pinned: false, messages: [], lastSnippet: 'Готово', lastAt: new Date().toISOString(), ...extra }) as unknown as Chat;

beforeEach(() => setLocale('uk'));
afterEach(() => setLocale('uk'));

describe('1. типова назва ПК мовою інтерфейсу', () => {
  it('«My computer» на uk — «Мій компʼютер», на cs — «Můj počítač»', () => {
    expect(displayTitle('My computer')).toBe('Мій компʼютер');
    expect(displayTitle('Můj počítač')).toBe('Мій компʼютер');
    expect(deviceName({ name: 'My computer', kind: 'pc' })).toBe('Мій компʼютер');
    setLocale('cs');
    expect(displayTitle('Мій компʼютер')).toBe('Můj počítač');
    setLocale('en');
    expect(displayTitle("Мій комп'ютер")).toBe('My computer');
  });

  it('назву, яку дала людина, не перекладаємо', () => {
    expect(displayTitle('Мій ноутбук')).toBe('Мій ноутбук');
    expect(displayTitle('My computer 2')).toBe('My computer 2');
    expect(deviceName({ name: 'Робочий', kind: 'pc' })).toBe('Робочий');
  });

  it('тека «Мої пристрої» на uk не показує англійське «My computer»', () => {
    render(
      <PhoneChatList chats={[chat('o', 'Оксана'), chat('pc', 'My computer', { mine: true })]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    const folder = document.querySelector('[data-folder-row="devices"]') as HTMLElement;
    expect(folder.textContent).toContain('Мій компʼютер');
    expect(document.body.textContent).not.toContain('My computer');
  });
});

describe('2. мій пристрій — знак пристрою', () => {
  it('відкрита тека: рядок ПК має знак пристрою, а людина — аватар', () => {
    render(
      <PhoneChatList chats={[chat('o', 'Оксана'), chat('pc', 'My computer', { mine: true })]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    fireEvent.click(document.querySelector('[data-folder-row="devices"]') as HTMLElement);
    const pc = document.querySelector('[data-chat-row="pc"]') as HTMLElement;
    expect(pc.querySelector('[data-device-avatar]')).not.toBeNull();
    expect(pc.querySelector('[data-row-avatar]')).toBeNull();
    const person = document.querySelector('[data-chat-row="o"]') as HTMLElement;
    expect(person.querySelector('[data-row-avatar]')).not.toBeNull();
  });
});

describe('3. рядки пристроїв у ритмі списку', () => {
  it('у теці «Додати свій пристрій» і «Увійти на сайті» — висота рядка списку 72', () => {
    (window as unknown as { PhantomBridge?: unknown }).PhantomBridge = { call: () => '{}', openWebSignIn: () => true };
    render(
      <PhoneChatList chats={[chat('o', 'Оксана'), chat('pc', 'My computer', { mine: true })]} currentUser={USER} onSelectChat={vi.fn()} onNewChat={vi.fn()} onOpenChatActions={vi.fn()} />,
    );
    fireEvent.click(document.querySelector('[data-folder-row="devices"]') as HTMLElement);
    const add = document.querySelector('[data-add-own-device]') as HTMLElement;
    expect(add.parentElement?.className).toContain('[&>button]:min-h-[72px]');
    expect(add.parentElement?.querySelector('[data-web-signin]')).not.toBeNull();
    delete (window as unknown as { PhantomBridge?: unknown }).PhantomBridge;
  });
});

describe('4. порожній «Сьогодні»', () => {
  const props = {
    calendar: 'granted' as const,
    nowMs: Date.now(),
    onClose: () => {},
    onDone: () => {},
    onDrop: () => {},
    onSnooze: () => {},
    onRemoveReminder: () => {},
    onOpenChat: () => {},
    onAskCalendar: () => {},
  };

  it('без справ аркуш за вмістом, а не на весь екран', () => {
    render(<DaySheet open items={[]} {...props} />);
    const dialog = document.querySelector('[data-testid="day-sheet"] [role="dialog"]') as HTMLElement;
    expect(dialog.className.split(/\s+/)).not.toContain('h-[calc(100%-48px)]');
  });
});

describe('5. плитки «Я»', () => {
  it('без підпису рядок підпису однаково тримає місце — назви на одній лінії', () => {
    const { container } = render(<MeTile testId="t" icon={Lock} title="Приватність" onClick={() => {}} />);
    expect(container.querySelector('[data-me-tile-value]')).not.toBeNull();
  });
});
