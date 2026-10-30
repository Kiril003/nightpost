import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, waitFor } from '@testing-library/react';

vi.mock('../utils/messengerInvite', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../utils/messengerInvite')>();
  return {
    ...actual,
    inviteInClipboard: vi.fn(async () => null),
    parseInvite: vi.fn(() => ({ kind: 'compact' as const, compact: 'PH2:test' })),
    addInvitedContact: vi.fn(async () => ({
      id: 'contact_dm1',
      display_name: 'Марія',
      peer_node_id: 'peer_dm1',
      verified: false,
      session_ready: false,
      reach: 'waiting' as const,
    })),
    isWaiting: vi.fn(() => true),
  };
});

vi.mock('../services/messengerApi', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/messengerApi')>();
  return {
    ...actual,
    messengerApi: {
      ...actual.messengerApi,
      listContacts: vi.fn(async () => []),
      sendRequest: vi.fn(async () => ({})),
      listRequests: vi.fn(async () => ({ incoming: [], outgoing: [] })),
      listConversations: vi.fn(async () => [
        {
          id: 'dm1',
          title: 'Марія',
          kind: 'dm',
          circle: 'all',
          contact_id: 'contact_dm1',
          created_at: '2026-10-14T10:00:00Z',
        },
      ]),
      createConversation: vi.fn(async () => ({
        id: 'dm1',
        title: 'Марія',
        kind: 'dm',
        circle: 'all',
        contact_id: 'contact_dm1',
        created_at: '2026-10-14T10:00:00Z',
      })),
      identity: vi.fn(async () => ({
        node_id: 'me',
        link: 'https://nightpost.app/i/me',
        compact: 'PH2:me',
        bundle: { name: 'Олег' },
      })),
    },
  };
});

import { CreateChatModal } from '../components/messenger/CreateChatModal';
import { messengerApi } from '../services/messengerApi';
import { LANGUAGE_SETTING_KEY, useSettingsStore } from '../stores/settingsStore';

beforeAll(() => {
  Element.prototype.scrollTo = Element.prototype.scrollTo ?? (() => {});
  Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
});

beforeEach(() => {
  useSettingsStore.setState({ values: { [LANGUAGE_SETTING_KEY]: 'uk' } });
});

afterEach(() => cleanup());

describe('add by invite sends a contact request', () => {
  it('pasting an invite and confirming asks the person to connect', async () => {
    render(<CreateChatModal isOpen onClose={() => {}} onConversationReady={() => {}} />);
    const door = await waitFor(() => {
      const btn = Array.from(document.querySelectorAll('button')).find((b) => b.textContent?.includes('Вставити запрошення'));
      expect(btn).toBeDefined();
      return btn!;
    });
    fireEvent.click(door);
    const field = document.querySelector('[data-testid="add-person-paste"] textarea') as HTMLTextAreaElement;
    fireEvent.change(field, { target: { value: 'PH2:test' } });
    const confirm = await waitFor(() => {
      const btn = Array.from(document.querySelectorAll('[data-testid="new-person-sheet"] button')).find(
        (b) => b.textContent?.trim() === 'Додати',
      ) as HTMLButtonElement | undefined;
      expect(btn?.disabled).toBe(false);
      return btn!;
    });
    fireEvent.click(confirm);
    await waitFor(() => expect(messengerApi.sendRequest).toHaveBeenCalledWith('contact_dm1'));
  });
});
