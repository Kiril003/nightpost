import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Message } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { MessageCard } from '../components/messenger/cards/MessageCard';
import { TimeOptionsEditor } from '../components/messenger/cards/CardEditors';

afterEach(() => {
  cleanup();
});

const t1730 = new Date(2025, 0, 10, 17, 30).getTime();
const t1900 = new Date(2025, 0, 10, 19, 0).getTime();
const iso18 = new Date(2025, 0, 10, 18, 0).toISOString();
const iso20 = new Date(2025, 0, 10, 20, 0).toISOString();

function makeTimePollMsg(overrides?: Partial<Message>): Message {
  return {
    id: 'm-time-1',
    senderId: 'u-author',
    senderName: 'Олена',
    senderAvatar: 'О',
    timestamp: '12:00',
    sentAt: '2025-01-10T09:06:40.000Z',
    isSelf: false,
    status: 'sent',
    type: 'widget:voting',
    votingData: {
      id: 'p1',
      question: 'Коли зустрінемось?',
      kind: 'time',
      options: [
        { id: 'o1', text: iso18, votes: 0, voters: [] },
        { id: 'o2', text: iso20, votes: 0, voters: [] },
      ],
      allowMultiple: false,
      totalVotes: 0,
    } as NonNullable<Message['votingData']>,
    ...overrides,
  };
}

describe('MessageCard time poll & TimeOptionsEditor', () => {
  it('1–3. pre-ticks free slot, does not write on option taps, and writes once on Confirm', () => {
    const sendCard = vi.fn().mockResolvedValue(undefined);
    const updateMessage = vi.fn();
    useMessengerStore.setState({ sendCard, updateMessage });

    const msg = makeTimePollMsg();
    render(<MessageCard msg={msg} meId="me" meName="Me" day={[{ start: t1730, end: t1900 }]} />);

    const row18 = screen.getByRole('button', { name: /18:00/ });
    const row20 = screen.getByRole('button', { name: /20:00/ });

    expect(row20.getAttribute('data-preticked')).toBe('true');
    expect(row18.hasAttribute('data-preticked')).toBe(false);

    fireEvent.click(row18);
    fireEvent.click(row20);

    expect(sendCard).not.toHaveBeenCalled();
    expect(updateMessage).not.toHaveBeenCalled();
    expect(row18.getAttribute('data-preticked')).toBe('true');
    expect(row20.hasAttribute('data-preticked')).toBe(false);

    fireEvent.click(screen.getByRole('button', { name: 'Підтвердити' }));

    expect(sendCard).toHaveBeenCalledTimes(1);
    const payload = sendCard.mock.calls[0][1] as NonNullable<Message['votingData']>;
    expect(payload.options[0].voters).toEqual(['me']);
    expect(payload.options[1].voters).toEqual([]);
  });

  it('4. without day, shows «Немає даних дня» and no [data-preticked] exists', () => {
    const msg = makeTimePollMsg();
    const { container } = render(<MessageCard msg={msg} meId="me" meName="Me" />);

    expect(screen.getByText('Немає даних дня')).toBeTruthy();
    expect(container.querySelector('[data-preticked]')).toBeNull();
  });

  it('5. text widget:voting poll still writes immediately on tap', () => {
    const sendCard = vi.fn().mockResolvedValue(undefined);
    const updateMessage = vi.fn();
    useMessengerStore.setState({ sendCard, updateMessage });

    const textPollMsg: Message = {
      ...makeTimePollMsg(),
      id: 'm-text-1',
      votingData: {
        id: 'p-text',
        question: 'Куди підемо?',
        options: [
          { id: 'a', text: 'Парк', votes: 0, voters: [] },
          { id: 'b', text: 'Кафе', votes: 0, voters: [] },
        ],
        allowMultiple: false,
        totalVotes: 0,
      },
    };

    render(<MessageCard msg={textPollMsg} meId="me" meName="Me" />);
    fireEvent.click(screen.getByRole('button', { name: /Парк/ }));

    expect(sendCard).toHaveBeenCalledTimes(1);
  });

  it('6. TimeOptionsEditor adds sorted ISO times, ignores duplicates, and removes options', () => {
    const onChange = vi.fn();
    render(<TimeOptionsEditor day="2025-01-10" options={[iso20]} onChange={onChange} />);

    const input = screen.getByTestId('time-add');
    fireEvent.change(input, { target: { value: '18:00' } });
    fireEvent.click(screen.getByRole('button', { name: 'Додати час' }));

    expect(onChange).toHaveBeenCalledWith([iso18, iso20]);

    onChange.mockClear();
    fireEvent.change(input, { target: { value: '20:00' } });
    fireEvent.keyDown(input, { key: 'Enter' });
    expect(onChange).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Прибрати 20:00' }));
    expect(onChange).toHaveBeenCalledWith([]);
  });
});
