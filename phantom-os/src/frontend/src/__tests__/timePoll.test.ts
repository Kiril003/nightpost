import { describe, expect, it, vi } from 'vitest';
import type { Message } from '../types/messenger';
import { useMessengerStore } from '../stores/messengerStore';
import { foldCards, pollOf, vote } from '../components/messenger/cards/cardModel';
import { confirm, preTick } from '../services/timePoll';

const t1730 = new Date(2025, 0, 10, 17, 30).getTime();
const t1900 = new Date(2025, 0, 10, 19, 0).getTime();
const iso18 = new Date(2025, 0, 10, 18, 0).toISOString();
const iso20 = new Date(2025, 0, 10, 20, 0).toISOString();

function makeTimePollMsg(overrides?: Partial<Message>): Message {
  return {
    id: 'm-poll-1',
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

describe('timePoll service & model', () => {
  it('1. preTick([18:00, 20:00], [{ start: 17:30, end: 19:00 }]) equals [false, true]', () => {
    const res = preTick([iso18, iso20], [{ start: t1730, end: t1900 }]);
    expect(res).toEqual([false, true]);
  });

  it('2. preTick(opts, null) is null and preTick(opts, []) is all true', () => {
    expect(preTick([iso18, iso20], null)).toBeNull();
    expect(preTick([iso18, iso20], [])).toEqual([true, true]);
  });

  it('3. does not write before confirm, then confirm sends widget:voting once with kind==="time"', () => {
    const sendCard = vi.fn().mockResolvedValue(undefined);
    const updateMessage = vi.fn();
    useMessengerStore.setState({ sendCard, updateMessage });

    const msg = makeTimePollMsg();
    const snapshot = structuredClone(msg.votingData);

    const parsed = pollOf(msg);
    const ticks = preTick(
      parsed!.options.map((o) => o.text),
      [{ start: t1730, end: t1900 }],
    );

    expect(ticks).toEqual([false, true]);
    expect(sendCard).not.toHaveBeenCalled();
    expect(updateMessage).not.toHaveBeenCalled();
    expect(msg.votingData).toEqual(snapshot);

    confirm(msg, [false, true], 'me');

    expect(sendCard).toHaveBeenCalledTimes(1);
    expect(updateMessage).not.toHaveBeenCalled();
    const [kindArg, payload] = sendCard.mock.calls[0] as [
      string,
      NonNullable<Message['votingData']> & { kind?: string },
    ];
    expect(kindArg).toBe('widget:voting');
    expect(payload.kind).toBe('time');
    expect(payload.options[0].voters).toEqual([]);
    expect(payload.options[1].voters).toEqual(['me']);
  });

  it('4. confirm moves my vote while keeping other voters', () => {
    const sendCard = vi.fn().mockResolvedValue(undefined);
    const updateMessage = vi.fn();
    useMessengerStore.setState({ sendCard, updateMessage });

    const msg = makeTimePollMsg({
      votingData: {
        id: 'p1',
        question: 'Коли?',
        kind: 'time',
        options: [
          { id: 'o1', text: iso18, votes: 1, voters: ['me'] },
          { id: 'o2', text: iso20, votes: 1, voters: ['ann'] },
        ],
        allowMultiple: false,
        totalVotes: 2,
      } as NonNullable<Message['votingData']>,
    });

    confirm(msg, [false, true], 'me');

    expect(sendCard).toHaveBeenCalledTimes(1);
    const payload = sendCard.mock.calls[0][1] as NonNullable<Message['votingData']>;
    expect(payload.options[0].voters).toEqual([]);
    expect(payload.options[1].voters).toEqual(['ann', 'me']);
  });

  it('5. pollOf returns kind: "time" and multiple: true even with allowMultiple: false; vote() keeps two picks', () => {
    const msg = makeTimePollMsg();
    const poll = pollOf(msg)!;
    expect(poll.kind).toBe('time');
    expect(poll.multiple).toBe(true);

    const afterFirst = vote(poll, 'o1', 'me');
    const afterSecond = vote(afterFirst, 'o2', 'me');
    expect(afterSecond.options[0].voters).toEqual(['me']);
    expect(afterSecond.options[1].voters).toEqual(['me']);
  });

  it('6. foldCards on two versions of p1 keeps kind: "time" in the shown message', () => {
    const v1 = makeTimePollMsg({ id: 'm1', senderId: 'u1' });
    const v2 = makeTimePollMsg({
      id: 'm2',
      senderId: 'u2',
      votingData: {
        id: 'p1',
        question: 'Коли зустрінемось?',
        kind: 'time',
        options: [
          { id: 'o1', text: iso18, votes: 0, voters: [] },
          { id: 'o2', text: iso20, votes: 1, voters: ['u2'] },
        ],
        allowMultiple: false,
        totalVotes: 1,
      } as NonNullable<Message['votingData']>,
    });

    const fold = foldCards([v1, v2]);
    const shown = fold.shown.get('m1')!;
    expect((shown.votingData as { kind?: unknown }).kind).toBe('time');
    expect(pollOf(shown)?.kind).toBe('time');
  });
});
