/**
 * A56 10.10 (_a56-grp2.png): у шапці групи «Friday pho» немає «Olena і ви», а
 * в творця A52 рядок зникає після перезапуску. Склад групи підтягували лише
 * при створенні, в профілі групи й у чернетці зустрічі — відкриття розмови
 * його не питало, а рядок вузла складу не несе.
 */
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { messengerApi, type NodeGroupMembers } from '../services/messengerApi';
import { useMessengerStore } from '../stores/messengerStore';
import { conv, mountRoot, useRootHooks } from './phoneFixRoot';

useRootHooks();

describe('9. шапка групи після перезапуску й у отримувача', () => {
  it('відкриття групи з кореня питає склад — «Olena і ви»', async () => {
    await mountRoot([conv('g', 'Friday pho', 'group', { last_kind: 'text', last_snippet: 'Hi', last_author: 'Olena' })]);
    const me = useMessengerStore.getState().currentUser.id;
    const asked = vi.spyOn(messengerApi, 'groupMembers').mockResolvedValue({
      id: 'g', title: 'Friday pho', authoritative: false, addable: [], max_members: 12,
      members: [
        { id: me, name: 'Kyrylo', is_me: true, role: 'owner' },
        { id: 'olena-node', name: 'Olena', is_me: false, role: 'member' },
      ],
    } as unknown as NodeGroupMembers);
    expect(useMessengerStore.getState().chats.find((c) => c.id === 'g')?.members).toBeUndefined();
    fireEvent.click(document.querySelector('[data-chat-row="g"]') as HTMLElement);
    await waitFor(() => expect(screen.getByTestId('header-plain-line').textContent).toBe('Olena і ви'));
    expect(asked).toHaveBeenCalledWith('g');
  });
});
