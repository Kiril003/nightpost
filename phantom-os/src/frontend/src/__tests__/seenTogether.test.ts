import { describe, it, expect } from 'vitest';
import { seenTogether } from '../services/seenTogether';
import { pinVenue, type CardMarks, type MeetupData } from '../types/meetup';
import type { Chat, Message } from '../types/messenger';

const ME = 'me';
const DAN = 'dan';

const meetupMsg = (
  id: string,
  at: number,
  cardMarks?: CardMarks,
  organizer = ME,
  extra: Partial<Message> = {},
): Message => {
  const meetupData: MeetupData = {
    v: 1,
    id,
    venue: pinVenue(50.45, 30.52, 'Каштани'),
    at,
    organizer,
    invitees: [
      { id: ME, name: 'Кирило' },
      { id: DAN, name: 'Дан' },
    ],
    expiresAt: null,
  };
  return {
    id: `msg_${id}`,
    senderId: organizer,
    senderName: 'Кирило',
    timestamp: '12:00',
    type: 'widget:meetup',
    isSelf: organizer === ME,
    meetupData,
    cardMarks,
    ...extra,
  } as unknown as Message;
};

const chat = (id: string, messages: Message[]): Chat =>
  ({
    id,
    title: id,
    type: 'group',
    messages,
  }) as unknown as Chat;

describe('seenTogether', () => {
  it('рахує дві спільні зустрічі з позначкою here:1 і бере новіший час', () => {
    const t1 = Date.UTC(2026, 8, 10, 18, 0);
    const t2 = Date.UTC(2026, 8, 20, 19, 0);
    const chats = [
      chat('g1', [
        meetupMsg('m2', t2, { 'here:1': [ME, DAN] }),
        meetupMsg('m1', t1, { 'here:1': [DAN, ME] }),
      ]),
    ];
    expect(seenTogether(chats, ME, DAN)).toEqual({ count: 2, lastAt: t2 });
  });

  it('не рахує план, де відмітився лише один з двох', () => {
    const t1 = Date.UTC(2026, 8, 10, 18, 0);
    const t2 = Date.UTC(2026, 8, 15, 18, 0);
    const t3 = Date.UTC(2026, 8, 20, 18, 0);
    const chats = [
      chat('g1', [
        meetupMsg('m1', t1, { 'here:1': [ME, DAN] }),
        meetupMsg('m2', t2, { 'here:1': [ME] }),
        meetupMsg('m3', t3, { 'here:1': [DAN] }),
      ]),
    ];
    expect(seenTogether(chats, ME, DAN)).toEqual({ count: 1, lastAt: t1 });
  });

  it('один і той самий id плану у двох розмовах рахується один раз', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    const chats = [
      chat('dm', [meetupMsg('m_same', at, { 'here:1': [ME, DAN] })]),
      chat('group', [meetupMsg('m_same', at, { 'here:1': [ME, DAN] })]),
    ];
    expect(seenTogether(chats, ME, DAN)).toEqual({ count: 1, lastAt: at });
  });

  it('скасований організатором план не рахується', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    const chats = [
      chat('g1', [meetupMsg('m_cancel', at, { 'here:1': [ME, DAN], 'cancel:1': [ME] }, ME)]),
    ];
    expect(seenTogether(chats, ME, DAN)).toBeNull();
  });

  it('повертає null коли планів або спільних позначок немає, чи порожній peerId', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    expect(seenTogether([], ME, DAN)).toBeNull();
    expect(seenTogether([chat('g1', [meetupMsg('m1', at)])], ME, DAN)).toBeNull();
    expect(seenTogether([chat('g1', [meetupMsg('m1', at, { 'here:1': [ME, DAN] })])], ME, '')).toBeNull();
    expect(seenTogether([chat('g1', [meetupMsg('m1', at, { 'here:1': [ME, DAN] })])], '', DAN)).toBeNull();
  });

  it('зводить позначки однієї зустрічі з різних розмов', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    const chats = [
      chat('dm', [meetupMsg('m_split', at, { 'here:1': [ME] })]),
      chat('group', [meetupMsg('m_split', at, { 'here:1': [DAN] })]),
    ];
    expect(seenTogether(chats, ME, DAN)).toEqual({ count: 1, lastAt: at });
  });

  it('скасування на іншій копії плану теж прибирає зустріч', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    const chats = [
      chat('dm', [meetupMsg('m_c2', at, { 'here:1': [ME, DAN] })]),
      chat('group', [meetupMsg('m_c2', at, { 'cancel:1': [ME] })]),
    ];
    expect(seenTogether(chats, ME, DAN)).toBeNull();
  });

  it('скасування не від організатора і видалені картки не рахуються як скасування чи зустріч', () => {
    const at = Date.UTC(2026, 8, 12, 17, 0);
    expect(seenTogether([chat('g', [meetupMsg('m_x', at, { 'here:1': [ME, DAN], 'cancel:1': [DAN] })])], ME, DAN)).toEqual({ count: 1, lastAt: at });
    expect(seenTogether([chat('g', [meetupMsg('m_d', at, { 'here:1': [ME, DAN] }, ME, { isDeleted: true })])], ME, DAN)).toBeNull();
  });
});
