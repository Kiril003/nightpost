import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { TaskSheet } from '../TaskSheet';
import { WorkBoard } from '../WorkBoard';
import { ShiftRoster } from '../ShiftRoster';
import { WorkHost } from '../WorkHost';
import { openWork } from '../../../../services/workBus';
import { parseTag, withTag, weekOf, ymd } from '../../../../services/workEvents';
import { workDirection } from '../../directions/work';
import { useMessengerStore } from '../../../../stores/messengerStore';
import { IVO, MARA, ME, PETRA, msg, seed, shiftMsg, taskMsg, workChat } from './workFixtures';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const TODAY = ymd(new Date());
const NEXT_DAY = weekOf(new Date())[(new Date().getDay() + 6) % 7 === 6 ? 5 : (new Date().getDay() + 6) % 7 + 1];
void NEXT_DAY;

describe('01 Лист → завдання', () => {
  it('аркуш бере текст листа, шле рядок із позначкою й посиланням на лист', () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(2030, 9, 21, 10, 0));
    const chat = workChat([msg('m1', IVO, 'Someone needs to cap the image size before decode')]);
    const sent = seed(chat);
    render(<TaskSheet chat={chat} srcMsgId="m1" onClose={vi.fn()} />);
    expect((screen.getByLabelText('Назва') as HTMLInputElement).value).toContain('cap the image size');
    fireEvent.click(screen.getByRole('radio', { name: /Mara/ }));
    fireEvent.click(screen.getByTestId('work-due'));
    fireEvent.click(screen.getByTestId('when-pick'));
    fireEvent.click(document.querySelector('[aria-current="date"]')!);
    fireEvent.click(screen.getByRole('button', { name: /^Обрати · / }));
    fireEvent.click(screen.getByRole('button', { name: 'Створити' }));
    expect(sent).toHaveLength(1);
    const tag = parseTag(sent[0])!;
    expect(tag.kind).toBe('task');
    expect(tag.f).toMatchObject({ to: MARA, src: 'm1' });
    expect(Number(tag.f.due)).toBeGreaterThan(Date.now());
    expect(ymd(new Date(Number(tag.f.due)))).toBe('2030-10-21');
    expect(tag.line.startsWith('☑ ')).toBe(true);
  });

  it('без назви кнопка вимкнена; у не-робочій групі нічого не шле', () => {
    const chat = workChat([], { circle: 'friends' });
    const sent = seed(chat);
    render(<TaskSheet chat={chat} onClose={vi.fn()} />);
    const create = screen.getByRole('button', { name: 'Створити' }) as HTMLButtonElement;
    expect(create.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Назва'), { target: { value: 'x' } });
    fireEvent.click(create);
    expect(sent).toHaveLength(0);
  });

  it('довгий дотик по листу в робочій групі: рядок «Зробити завданням» відкриває цей аркуш через двері шапки', async () => {
    const chat = workChat([msg('m1', IVO, 'Ship it by Friday')]);
    seed(chat);
    render(<WorkHost chatId="w1" />);
    expect(screen.queryByTestId('work-task-sheet')).toBeNull();
    act(() => openWork({ chatId: 'w1', taskFrom: 'm1' }));
    expect(screen.getByTestId('work-task-sheet')).toBeTruthy();
    expect((screen.getByLabelText('Назва') as HTMLInputElement).value).toBe('Ship it by Friday');
  });
});

describe('02–03 Картка завдання і дошка', () => {
  const chat = () =>
    workChat([
      msg('m1', IVO, 'Cap the image size before decode'),
      msg('m2', MARA, withTag('☑ Cap image → Me', 'task', { id: 't1', to: ME, src: 'm1' })),
      taskMsg('t2', 'Store listing text', 'petra'),
      msg('m3', PETRA, withTag('↻ x', 'task-status', { id: 't2', s: 'review' })),
    ]);

  it('дошка ділить завдання за статусами з лічильниками; порожній статус каже про це', () => {
    seed(chat());
    render(<WorkBoard chatId="w1" />);
    expect(screen.getByText('Cap image')).toBeTruthy();
    expect(screen.queryByText('Store listing text')).toBeNull();
    fireEvent.click(screen.getByRole('tab', { name: /Перевірка/ }));
    expect(screen.getByText('Store listing text')).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: /Готово/ }));
    expect(document.querySelector('[data-work-empty]')).toBeTruthy();
  });

  it('картка показує лист-першоджерело і веде завдання на крок уперед статусним рядком', () => {
    const sent = seed(chat());
    render(<WorkBoard chatId="w1" />);
    fireEvent.click(screen.getByText('Cap image'));
    const sheet = screen.getByTestId('work-task-card');
    expect(within(sheet).getByText(/Cap the image size before decode/)).toBeTruthy();
    fireEvent.click(within(sheet).getByRole('button', { name: 'Взяти в роботу' }));
    expect(parseTag(sent[0])).toMatchObject({ kind: 'task-status', f: { id: 't1', s: 'doing' } });
  });

  it('чужому завданню кроків немає, «+ Завдання» відкриває форму', () => {
    seed(chat());
    render(<WorkBoard chatId="w1" />);
    fireEvent.click(screen.getByRole('tab', { name: /Перевірка/ }));
    fireEvent.click(screen.getByText('Store listing text'));
    expect(within(screen.getByTestId('work-task-card')).queryByRole('button', { name: 'Готово' })).toBeNull();
    cleanup();
    seed(chat());
    render(<WorkBoard chatId="w1" />);
    fireEvent.click(screen.getByRole('button', { name: /Завдання/ }));
    expect(screen.getByTestId('work-task-sheet')).toBeTruthy();
  });
});

describe('04 Графік змін', () => {
  it('день показує зміни, вільну можна взяти, мої години тижня — з даних', () => {
    const sent = seed(
      workChat([
        shiftMsg('a', ME, TODAY, '07:00', '15:00'),
        shiftMsg('b', '', TODAY, '16:00', '22:00', 'Floor'),
      ]),
    );
    render(<ShiftRoster chatId="w1" />);
    expect(screen.getByText('07:00–15:00')).toBeTruthy();
    expect(screen.getByText('Мій тиждень 8 год')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Взяти' }));
    expect(parseTag(sent[0])).toMatchObject({ kind: 'take', f: { id: 'b' } });
  });

  it('порожній графік каже «Змін немає»; планувальник бачить «Зміна», учасник — ні', () => {
    seed(workChat([], { ownerId: PETRA }));
    render(<ShiftRoster chatId="w1" />);
    expect(screen.getByText('Змін немає')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Зміна' })).toBeNull();
    cleanup();
    seed(workChat([], { ownerId: ME }));
    render(<ShiftRoster chatId="w1" />);
    fireEvent.click(screen.getByRole('button', { name: 'Зміна' }));
    expect(screen.getByTestId('work-add-shift')).toBeTruthy();
  });
});

describe('05 Обмін змінами', () => {
  const week = weekOf(new Date());
  const [d1, d2] = [week[1], week[2]];
  const shifts = () => [shiftMsg('a', ME, d1, '10:00', '18:00'), shiftMsg('b', MARA, d2, '10:00', '18:00')];

  it('пропозиція зі своєї зміни: вибір чужої, перевірки годин і відпочинку, рядок swap', () => {
    const sent = seed(workChat(shifts()));
    render(<ShiftRoster chatId="w1" />);
    fireEvent.click(screen.getAllByRole('tab')[1]);
    fireEvent.click(screen.getByRole('button', { name: 'Обміняти' }));
    const sheet = screen.getByTestId('work-swap-sheet');
    fireEvent.click(within(sheet).getByRole('radio', { name: /Mara/ }));
    expect(within(sheet).getAllByText(/год/).length).toBeGreaterThan(0);
    fireEvent.click(within(sheet).getByRole('button', { name: 'Обміняти' }));
    expect(parseTag(sent[0])).toMatchObject({ kind: 'swap', f: { a: 'a', b: 'b', to: MARA } });
  });

  it('вхідний обмін: «Прийняти обмін» шле accepted, часова лінія чесна, стороннього кнопок немає', () => {
    const offer = msg('w', MARA, withTag('swap', 'swap', { id: 'w1', a: 'b', b: 'a', to: ME }));
    const sent = seed(workChat([...shifts(), offer]));
    render(<ShiftRoster chatId="w1" />);
    fireEvent.click(document.querySelector('[data-work-swap]')!);
    const sheet = screen.getByTestId('work-swap-sheet');
    expect(sheet.querySelectorAll('[data-work-step="done"]')).toHaveLength(1);
    fireEvent.click(within(sheet).getByRole('button', { name: 'Прийняти обмін' }));
    expect(parseTag(sent[0])).toMatchObject({ kind: 'swap-ack', f: { id: 'w1', s: 'accepted' } });
    cleanup();
    const other = msg('w', MARA, withTag('swap', 'swap', { id: 'w1', a: 'b', b: 'a', to: ME }));
    seed(workChat([...shifts(), other], { ownerId: PETRA }));
    useMessengerStore.setState({ currentUser: { id: IVO, name: 'Ivo', avatar: '' } as never });
    render(<ShiftRoster chatId="w1" />);
    fireEvent.click(document.querySelector('[data-work-swap]')!);
    const sheet2 = screen.getByTestId('work-swap-sheet');
    expect(within(sheet2).queryByRole('button', { name: 'Прийняти обмін' })).toBeNull();
    expect(sheet2.querySelector('[data-work-state="offered"]')).toBeTruthy();
  });
});

describe('напрям «Робота»', () => {
  it('реєстр: вкладки й швидкі дії закріплені, екрани — компоненти', () => {
    expect(workDirection.tabs).toEqual(['chats', 'tasks', 'shifts', 'me']);
    expect(workDirection.quickActions.map((a) => a.id)).toEqual(['letter-to-task', 'my-shift', 'swap']);
    expect(typeof workDirection.screens.tasks).toBe('function');
    expect(typeof workDirection.screens.shifts).toBe('function');
  });

  it('двері шапки: WorkHost відкриває хаб із вкладками «Завдання» і «Зміни»', () => {
    seed(workChat([shiftMsg('a', ME, TODAY, '07:00', '15:00')]));
    render(<WorkHost chatId="w1" />);
    act(() => openWork({ chatId: 'w1', view: 'shifts' }));
    expect(screen.getByTestId('work-hub')).toBeTruthy();
    expect(screen.getByRole('tab', { name: 'Зміни', selected: true })).toBeTruthy();
    fireEvent.click(screen.getByRole('tab', { name: 'Завдання' }));
    expect(document.querySelector('[data-work-board]')).toBeTruthy();
  });
});
