import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanSheet } from '../components/messenger/meetup/PlanSheet';
import { contactForPeer, needsVerify } from '../components/messenger/VerifyContact';
import { messengerApi, type NodeContact } from '../services/messengerApi';
import { HERE_VALUE } from '../services/meetup/marks';
import { useMessengerStore } from '../stores/messengerStore';
import type { MeetupData } from '../types/meetup';
import type { Chat, Message } from '../types/messenger';

vi.mock('../services/messengerApi', () => ({
  messengerApi: {
    listContacts: vi.fn(),
    verifyContact: vi.fn(),
  },
}));

const SAFETY_60 = '123451234512345123451234512345123451234512345123451234512345';
const SAFETY_PRETTY =
  '12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345 12345';

const makeContact = (
  overrides: Partial<NodeContact> &
    Pick<NodeContact, 'id' | 'peer_node_id' | 'verified' | 'key_changed'>,
): NodeContact =>
  ({
    safety_number: SAFETY_60,
    safety_number_pretty: SAFETY_PRETTY,
    fingerprint: '00112233445566778899AABBCCDDEEFF',
    peer_address: '127.0.0.1:9000',
    ...overrides,
  }) as NodeContact;

const hereMarks = (ids: string[]): Message['cardMarks'] =>
  ids.length === 0 ? undefined : { [HERE_VALUE]: ids };

const makeMeetup = (): MeetupData =>
  ({
    id: 'meetup-1',
    organizer: 'me',
    at: Date.now() + 5 * 3600_000,
    venue: {
      id: 'venue-1',
      name: 'Кафе Липа',
      lat: 50.45,
      lon: 30.52,
    },
    invitees: [
      { id: 'me', name: 'Я' },
      { id: 'p1', name: 'Олена' },
    ],
  }) as MeetupData;

const seedPlanChat = (checkedInIds: string[]): { msg: Message; meetup: MeetupData } => {
  const meetup = makeMeetup();
  const msg: Message = {
    id: 'msg-meet-1',
    senderId: 'me',
    senderName: 'Я',
    senderAvatar: '',
    text: 'Зустріч у Кафе Липа',
    type: 'text',
    timestamp: '12:00',
    cardMarks: hereMarks(checkedInIds),
  };
  const chat: Chat = {
    id: 'chat-plan-1',
    title: 'Кафе Липа',
    avatar: '',
    type: 'group',
    unreadCount: 0,
    circle: 'all',
    messages: [msg],
  };
  useMessengerStore.setState({
    chats: [chat],
    activeChatId: chat.id,
  });
  return { msg, meetup };
};

describe('chats-21: verify keys at meetup (PlanSheet)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
  });

  it('1. shows «Звірити ключі» when both me and peer are here and peer is unverified', async () => {
    const c = makeContact({ id: 'c-p1', peer_node_id: 'p1', verified: false, key_changed: false });
    vi.mocked(messengerApi.listContacts).mockResolvedValue([c]);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    const btn = await screen.findByTestId('plan-verify-p1');
    expect(btn.textContent).toBe('Звірити ключі');
    expect(screen.getByTestId('plan-verify-row-p1')).toBeTruthy();
    expect(screen.getByTestId('plan-verify')).toBeTruthy();
  });

  it('2. tapping «Звірити ключі» opens the QR panel directly inside plan-sheet', async () => {
    const c = makeContact({ id: 'c-p1', peer_node_id: 'p1', verified: false, key_changed: false });
    vi.mocked(messengerApi.listContacts).mockResolvedValue([c]);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    const btn = await screen.findByTestId('plan-verify-p1');
    fireEvent.click(btn);

    const sheet = screen.getByTestId('plan-sheet');
    expect(sheet.querySelector('[data-safety-qr-open]')).not.toBeNull();
  });

  it('3. hides plan-verify section when peer is already verified without key change', async () => {
    const c = makeContact({ id: 'c-p1', peer_node_id: 'p1', verified: true, key_changed: false });
    vi.mocked(messengerApi.listContacts).mockResolvedValue([c]);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    await waitFor(() => {
      expect(messengerApi.listContacts).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId('plan-verify')).toBeNull();
    expect(needsVerify(c)).toBe(false);
  });

  it('4. shows row when key_changed is true even if verified is true or an older row is verified', async () => {
    const olderVerified = makeContact({
      id: 'c-old',
      peer_node_id: 'p1',
      verified: true,
      key_changed: false,
    });
    const newerChanged = makeContact({
      id: 'c-new',
      peer_node_id: 'p1',
      verified: true,
      key_changed: true,
    });
    expect(contactForPeer([olderVerified, newerChanged], 'p1')?.id).toBe('c-new');
    expect(needsVerify(newerChanged)).toBe(true);

    vi.mocked(messengerApi.listContacts).mockResolvedValue([olderVerified, newerChanged]);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    const btn = await screen.findByTestId('plan-verify-p1');
    expect(btn.textContent).toBe('Звірити ключі');
  });

  it('5. does not render section or call listContacts when only the peer is here', () => {
    const { msg, meetup } = seedPlanChat(['p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    expect(screen.queryByTestId('plan-verify')).toBeNull();
    expect(messengerApi.listContacts).not.toHaveBeenCalled();
  });

  it('6. does not render section or call listContacts when only me is here', () => {
    const { msg, meetup } = seedPlanChat(['me']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    expect(screen.queryByTestId('plan-verify')).toBeNull();
    expect(messengerApi.listContacts).not.toHaveBeenCalled();
  });

  it('7. hides section when peer has no contact row or when listContacts rejects', async () => {
    vi.mocked(messengerApi.listContacts).mockResolvedValueOnce([]);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    const { unmount } = render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    await waitFor(() => {
      expect(messengerApi.listContacts).toHaveBeenCalledTimes(1);
    });
    expect(screen.queryByTestId('plan-verify')).toBeNull();
    unmount();

    vi.mocked(messengerApi.listContacts).mockRejectedValueOnce(new Error('node unreachable'));
    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    await waitFor(() => {
      expect(messengerApi.listContacts).toHaveBeenCalledTimes(2);
    });
    expect(screen.queryByTestId('plan-verify')).toBeNull();
  });

  it('8. removes the peer row after confirming verification in SafetyVerifyBlock', async () => {
    const c = makeContact({ id: 'c-p1', peer_node_id: 'p1', verified: false, key_changed: false });
    const verifiedContact = { ...c, verified: true, key_changed: false };
    vi.mocked(messengerApi.listContacts).mockResolvedValue([c]);
    vi.mocked(messengerApi.verifyContact).mockResolvedValue(verifiedContact);
    const { msg, meetup } = seedPlanChat(['me', 'p1']);

    render(
      <PlanSheet
        msg={msg}
        meetup={meetup}
        meId="me"
        onClose={() => {}}
        onOpenMap={() => {}}
        onRate={() => {}}
      />,
    );

    const openBtn = await screen.findByTestId('plan-verify-p1');
    fireEvent.click(openBtn);

    const sheet = screen.getByTestId('plan-sheet');
    const confirmBtn = sheet.querySelector<HTMLButtonElement>('[data-safety-confirm]');
    expect(confirmBtn).not.toBeNull();
    fireEvent.click(confirmBtn!);

    await waitFor(() => {
      expect(messengerApi.verifyContact).toHaveBeenCalledWith('c-p1');
      expect(screen.queryByTestId('plan-verify-p1')).toBeNull();
      expect(screen.queryByTestId('plan-verify')).toBeNull();
    });
  });
});
