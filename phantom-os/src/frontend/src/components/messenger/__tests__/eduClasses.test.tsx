import type React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { ClassesScreen } from '../directions/education/ClassesScreen';
import { EduHost } from '../directions/education/EduHost';
import { EduEventCard } from '../directions/education/EduEventCard';
import { PhoneChatMenu } from '../PhoneChatMenu';
import { MessageBody } from '../feed/MessageBody';
import type { FeedRow } from '../feed/feedModel';
import { FeedActionsContext, type FeedActions } from '../feed/feedActions';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useProfiles } from '../../../stores/profilesStore';
import { openClass, useEduNav } from '../../../services/edu/eduNav';
import { parseEdu } from '../../../services/edu/eduWire';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import education from '../directions/education';
import { classEv, evMsg, fakeSend, GROUP, resetEdu, seed, TEACHER } from './eduKit';

let prev: ReturnType<typeof useMessengerStore.getState>;
let prevProfiles: ReturnType<typeof useProfiles.getState>;
beforeEach(() => {
  prev = useMessengerStore.getState();
  prevProfiles = useProfiles.getState();
  resetEdu();
});
afterEach(() => {
  cleanup();
  resetEdu();
  useMessengerStore.setState(prev, true);
  useProfiles.setState(prevProfiles, true);
});

describe('Класи: список', () => {
  it('без класів — чесний порожній стан і дія «Обрати групу»', () => {
    seed('teacher');
    render(<ClassesScreen />);
    expect(screen.getByTestId('edu-classes-empty').textContent).toContain('Класів ще немає');
    expect(screen.queryByTestId('edu-tile-assign')).toBeNull();
    fireEvent.click(screen.getByText('Обрати групу'));
    expect(screen.getByTestId('edu-make-class')).toBeTruthy();
  });

  it('учитель робить групу класом: на дріт іде читабельний лист із міткою «клас»', () => {
    seed('teacher');
    const send = fakeSend();
    render(<ClassesScreen />);
    fireEvent.click(screen.getByTestId('edu-classes-add'));
    fireEvent.click(screen.getByText('9-Б Фізика'));
    expect((screen.getByTestId('edu-make-name') as HTMLInputElement).value).toBe('9-Б Фізика');
    fireEvent.change(screen.getByTestId('edu-make-name'), { target: { value: '9-Б' } });
    fireEvent.change(screen.getByTestId('edu-make-subject'), { target: { value: 'Фізика' } });
    fireEvent.click(screen.getByTestId('edu-make-create'));
    expect(send).toHaveBeenCalledTimes(1);
    const [text, opts] = send.mock.calls[0] as [string, { to: string }];
    expect(opts.to).toBe(GROUP);
    expect(text.split('\n')[0]).toBe('Клас · 9-Б');
    expect(parseEdu(text)?.ev).toMatchObject({ k: 'class', n: '9-Б', s: 'Фізика' });
  });

  it('клас у списку: назва, предмет без завдань, дії над класом, тап відкриває головну класу', () => {
    seed('teacher', [evMsg(TEACHER, classEv(), ['Клас · 9-Б'], '', true)]);
    render(
      <>
        <ClassesScreen />
        <EduHost />
      </>,
    );
    const row = screen.getByTestId(`edu-class-${GROUP}`);
    // T59: дві дії — у сітці на чотири, як плитки вкладень, а не по краях екрана.
    expect(screen.getByTestId('edu-tiles').className).toContain('grid-cols-4');
    expect(row.textContent).toContain('9-Б');
    expect(row.textContent).toContain('Фізика');
    fireEvent.click(screen.getByTestId('edu-tile-attendance'));
    expect(useEduNav.getState().stack.at(-1)).toEqual({ s: 'attendance', chatId: GROUP });
    resetEdu();
    fireEvent.click(row);
    expect(screen.getByTestId('edu-class-home').textContent).toContain('Завдань немає');
    expect(screen.getByTestId('edu-home-new')).toBeTruthy();
  });

  it('учень не бачить кнопки «Нове завдання»; чужий «клас» від учня класом не робить групу вчителем', () => {
    seed('student', [evMsg('st1', classEv(), ['Клас · 9-Б'])]);
    openClass(GROUP);
    render(<EduHost />);
    expect(screen.queryByTestId('edu-home-new')).toBeNull();
  });
});

describe('Вхід: шапка групи й стрічка', () => {
  it('у профілі «Навчання» меню групи має двері «Зробити класом»; у класі — «Клас» і веде на головну', () => {
    const st = useProfiles.getState();
    const active = st.profiles.find((p) => p.id === st.activeId)!;
    useProfiles.setState({ profiles: st.profiles.map((p) => (p.id === active.id ? { ...p, kind: 'study' as const } : p)) });
    const chat = seed('teacher');
    const onClose = vi.fn();
    render(
      <>
        <PhoneChatMenu chat={chat} onClose={onClose} onOpenCard={vi.fn()} onOpenGroupDetails={vi.fn()} />
        <EduHost />
      </>,
    );
    expect(screen.getByText('Зробити класом')).toBeTruthy();
    cleanup();

    const classed = seed('teacher', [evMsg(TEACHER, classEv(), ['Клас · 9-Б'], '', true)]);
    render(
      <>
        <PhoneChatMenu chat={classed} onClose={onClose} onOpenCard={vi.fn()} onOpenGroupDetails={vi.fn()} />
        <EduHost />
      </>,
    );
    fireEvent.click(screen.getByText('Клас'));
    expect(onClose).toHaveBeenCalled();
    expect(screen.getByTestId('edu-class-home')).toBeTruthy();
  });

  it('поза профілем «Навчання» звичайна група дверей не має', () => {
    const chat = seed('teacher');
    render(<PhoneChatMenu chat={chat} onClose={vi.fn()} onOpenCard={vi.fn()} onOpenGroupDetails={vi.fn()} />);
    expect(screen.queryByText('Зробити класом')).toBeNull();
  });

  it('у стрічці лист класу — картка з кнопкою «Відкрити», а не рядок із міткою', () => {
    const m = evMsg(TEACHER, classEv(), ['Клас · 9-Б'], '', true);
    seed('teacher', [m]);
    render(
      <>
        <EduEventCard parsed={parseEdu(m.text)!} chatId={GROUP} senderId={TEACHER} text="Клас · 9-Б" />
        <EduHost />
      </>,
    );
    expect(screen.getByTestId('edu-card-class').textContent).not.toContain('⟦');
    fireEvent.click(screen.getByTestId('edu-card-open'));
    expect(screen.getByTestId('edu-class-home')).toBeTruthy();
  });

  it('MessageBody: лист навчання малює картку, звичайний текст лишається текстом', () => {
    const m = evMsg(TEACHER, classEv(), ['Клас · 9-Б']);
    seed('student', [m]);
    const row = (msg: typeof m) => ({ msg, isSelf: m.isSelf, cardView: false, density: 'normal' }) as unknown as FeedRow;
    const props = { chatId: GROUP, currentUserId: 'me', currentUserName: 'Я', phone: true, isActiveSearchMatch: false, expanded: false, copied: false };
    const wrap = (node: React.ReactNode) => <FeedActionsContext.Provider value={{} as FeedActions}>{node}</FeedActionsContext.Provider>;
    const { rerender } = render(wrap(<MessageBody row={row(m)} {...props} />));
    expect(screen.getByTestId('edu-card-class')).toBeTruthy();
    expect(document.body.textContent).not.toContain('⟦edu1');
    rerender(wrap(<MessageBody row={row({ ...m, text: 'Привіт, клас' })} {...props} />));
    expect(screen.queryByTestId('edu-card-class')).toBeNull();
    expect(document.body.textContent).toContain('Привіт, клас');
  });

  it('шар екранів змонтовано в корені месенджера', () => {
    expect(readFileSync(join(process.cwd(), 'src/components/messenger/MessengerRoot.tsx'), 'utf8')).toContain('<EduHost />');
  });

  it('напрямок віддає профілям вкладки, дії й екрани; «Оцінок» немає, бо екрана немає', () => {
    expect(education.tabs).toEqual(['chats', 'classes', 'schedule', 'me']);
    expect(education.quickActions).toEqual(['assign', 'attendance']);
    expect(Object.keys(education.screens)).toEqual(
      expect.arrayContaining(['classes', 'home', 'assign', 'assignment', 'attendance', 'forum', 'thread', 'host']),
    );
  });
});
