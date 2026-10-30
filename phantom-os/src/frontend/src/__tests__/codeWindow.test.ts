import { beforeEach, describe, expect, it } from 'vitest';
import {
  codeWindowEnd,
  doorCodeOf,
  dueCodes,
  isDoorCode,
  shutCodes,
} from '../services/codeWindow';
import { useMessengerStore } from '../stores/messengerStore';
import type { Chat, Message } from '../types/messenger';

const wedMorning = new Date(2026, 2, 4, 10, 0, 0, 0);

function makeMessage(overrides: Partial<Message> = {}): Message {
  return {
    id: 'msg-1',
    senderId: 'peer-1',
    senderName: 'Олена',
    senderAvatar: '',
    timestamp: '10:00',
    type: 'text',
    text: 'code: 4829# until Sun',
    sentAt: wedMorning.toISOString(),
    ...overrides,
  };
}

describe('codeWindow', () => {
  beforeEach(() => {
    shutCodes.reset();
  });

  it('parses English door code until short weekday to Sunday 23:59:59.999 local', () => {
    const res = doorCodeOf('code: 4829# until Sun', wedMorning);
    expect(res).not.toBeNull();
    expect(res!.code).toBe('4829#');
    expect(res!.until).toEqual(new Date(2026, 2, 8, 23, 59, 59, 999));
  });

  it('parses Ukrainian door code with genitive weekday to the same Sunday', () => {
    const res = doorCodeOf('код: 1234 до неділі', wedMorning);
    expect(res).not.toBeNull();
    expect(res!.code).toBe('1234');
    expect(res!.until).toEqual(new Date(2026, 2, 8, 23, 59, 59, 999));
  });

  it('parses clock deadline on the same day via findTime', () => {
    const res = doorCodeOf('код 1234 до 20:00', wedMorning);
    expect(res).not.toBeNull();
    expect(res!.code).toBe('1234');
    expect(res!.until).toEqual(new Date(2026, 2, 4, 20, 0, 0, 0));
  });

  it('weekday with a clock ends at that clock on that weekday', () => {
    const res = doorCodeOf('код 1234 до неділі 20:00', wedMorning);
    expect(res!.until).toEqual(new Date(2026, 2, 8, 20, 0, 0, 0));
  });

  it('a code that looks like a clock is not read as the window end', () => {
    const res = doorCodeOf('code: 2030 until Sun', wedMorning);
    expect(res!.code).toBe('2030');
    expect(res!.until).toEqual(new Date(2026, 2, 8, 23, 59, 59, 999));
  });

  it('resolves same-weekday window to next week (+7 days)', () => {
    const res = doorCodeOf('code: 9999 until Wed', wedMorning);
    expect(res).not.toBeNull();
    expect(res!.code).toBe('9999');
    expect(res!.until).toEqual(new Date(2026, 2, 11, 23, 59, 59, 999));
  });

  it('returns null when either code or window is missing', () => {
    expect(doorCodeOf('code: 4829#', wedMorning)).toBeNull();
    expect(doorCodeOf('until Sun', wedMorning)).toBeNull();
  });

  it('computes dueCodes across self and peer copies and skips already deleted messages', () => {
    const selfCopy = makeMessage({ id: 'm-self', senderId: 'me', isSelf: true });
    const peerCopy = makeMessage({ id: 'm-peer', senderId: 'peer-1', isSelf: false });
    const deletedCopy = makeMessage({ id: 'm-del', isDeleted: true });
    const end = codeWindowEnd(selfCopy)!;

    expect(end).toBe(new Date(2026, 2, 8, 23, 59, 59, 999).getTime());
    expect(dueCodes([selfCopy, peerCopy, deletedCopy], end - 1)).toEqual([]);
    expect(dueCodes([selfCopy, peerCopy, deletedCopy], end)).toEqual(['m-self', 'm-peer']);
  });

  it('a reused id on a live message is not a door code just because it was shut once', () => {
    shutCodes.add('reused');
    expect(isDoorCode(makeMessage({ id: 'reused', text: 'привіт' }))).toBe(false);
    expect(isDoorCode(makeMessage({ id: 'reused', text: undefined, isDeleted: true }))).toBe(true);
  });

  it('purges both sender and receiver copies at window end via sweepCodeWindows', () => {
    const selfCopy = makeMessage({ id: 'm-self', senderId: 'me', isSelf: true });
    const peerCopy = makeMessage({ id: 'm-peer', senderId: 'peer-1', isSelf: false });
    const end = codeWindowEnd(selfCopy)!;
    const chat: Chat = {
      id: 'chat-door',
      title: 'Підʼїзд',
      avatar: '',
      type: 'dm',
      circle: 'all',
      unreadCount: 0,
      messages: [selfCopy, peerCopy],
    };

    useMessengerStore.setState({ chats: [chat], activeChatId: 'other-chat' });

    useMessengerStore.getState().sweepCodeWindows(end - 1);
    let current = useMessengerStore.getState().chats.find((c) => c.id === 'chat-door')!;
    expect(current.messages[0].isDeleted).toBeFalsy();
    expect(current.messages[0].text).toBe('code: 4829# until Sun');
    expect(current.messages[1].isDeleted).toBeFalsy();
    expect(current.messages[1].text).toBe('code: 4829# until Sun');

    useMessengerStore.getState().sweepCodeWindows(end);
    current = useMessengerStore.getState().chats.find((c) => c.id === 'chat-door')!;
    expect(current.messages[0].isDeleted).toBe(true);
    expect(current.messages[0].text).toBeUndefined();
    expect(current.messages[1].isDeleted).toBe(true);
    expect(current.messages[1].text).toBeUndefined();
    expect(JSON.stringify(current)).not.toContain('4829');
    expect(shutCodes.has('m-self')).toBe(true);
    expect(shutCodes.has('m-peer')).toBe(true);
    expect(isDoorCode(current.messages[0])).toBe(true);
  });

  it('persists shutCodes across reset/add and recovers from corrupt storage', () => {
    shutCodes.reset();
    expect(shutCodes.all().size).toBe(0);

    shutCodes.add('door-1');
    expect(shutCodes.has('door-1')).toBe(true);
    expect(shutCodes.all().has('door-1')).toBe(true);

    localStorage.setItem('np.codeWindow.shut.v1', '{not-valid-json');
    expect(shutCodes.all().size).toBe(0);
    expect(shutCodes.has('door-1')).toBe(false);
  });
});
