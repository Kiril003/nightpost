import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MessageActionSheet, type MessageSheetHandlers } from '../../feed/MessageActionSheet';
import { Header } from '../../Header';
import { IVO, ME, msg, seed, workChat } from './workFixtures';
import type { UserProfile } from '../../../../types/messenger';

const noop = () => {};
const on = Object.fromEntries(
  ['reply', 'copyText', 'forward', 'edit', 'react', 'openReactionPicker', 'speak', 'openCanvas', 'toast', 'translate', 'summarize', 'actionItems', 'togglePin', 'bookmark', 'select', 'inspect', 'remove'].map((k) => [k, vi.fn()]),
) as unknown as MessageSheetHandlers;

const sheet = (chat: ReturnType<typeof workChat>, m = chat.messages[0]) =>
  render(<MessageActionSheet msg={m} currentChat={chat} currentUserId={ME} speaking={false} onClose={noop} on={on} />);

const header = (chat: ReturnType<typeof workChat>) =>
  render(
    <MemoryRouter>
      <Header currentChat={chat} currentUser={{ id: ME, name: 'Me' } as unknown as UserProfile} onOpenSettings={noop} onOpenGroupDetails={noop} isSoundEnabled onToggleSound={noop} onToggleSearch={noop} isSearching={false} />
    </MemoryRouter>,
  );

describe('входи в «Роботу»', () => {
  afterEach(cleanup);

  it('довгий дотик у робочій групі: «Зробити завданням» є, у чужому колі і на службовому рядку — ні', () => {
    sheet(workChat([msg('m1', IVO, 'Fix it')]));
    expect(screen.getByText('Зробити завданням')).toBeTruthy();
    cleanup();
    sheet(workChat([msg('m1', IVO, 'Fix it')], { circle: 'friends' }));
    expect(screen.queryByText('Зробити завданням')).toBeNull();
    cleanup();
    sheet(workChat([msg('m1', IVO, 'x\n[np:task id=t1 to=me]')]));
    expect(screen.queryByText('Зробити завданням')).toBeNull();
  });

  it('шапка робочої групи має кнопку «Робота» і відкриває хаб; у друзях її нема', () => {
    const chat = workChat([msg('m1', IVO, 'hi')]);
    seed(chat);
    header(chat);
    fireEvent.click(document.querySelector('[data-work-open]')!);
    expect(screen.getByTestId('work-hub')).toBeTruthy();
    cleanup();
    header(workChat([], { circle: 'friends' }));
    expect(document.querySelector('[data-work-open]')).toBeNull();
  });

  it('рядок меню відкриває форму завдання з текстом листа', () => {
    const chat = workChat([msg('m1', IVO, 'Fix the decoder')]);
    seed(chat);
    header(chat);
    sheet(chat);
    fireEvent.click(screen.getByText('Зробити завданням'));
    expect((screen.getByLabelText('Назва') as HTMLInputElement).value).toBe('Fix the decoder');
  });
});
