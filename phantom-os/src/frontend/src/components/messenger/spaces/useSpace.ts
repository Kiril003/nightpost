import { useEffect, useMemo, useState } from 'react';
import type { Space } from '@shared/types/space';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useProfiles } from '../../../stores/profilesStore';
import { ownerOf, useChatProfiles } from '../../../stores/chatProfileStore';
import { foldSpace } from '../../../services/spaces/spaceLog';
import { spaceLog } from '../../../services/spaces/spaceVault';
import { pullSpace } from '../../../services/spaces/spaceWire';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import type { Chat } from '../../../types/messenger';

export interface SpaceView {
  chat: Chat | undefined;
  space: Space | null;
  meId: string;
  profileId: string;
  /** `loading` — журнал ще не питали; `failed` — міст відмовив, показуємо те, що є. */
  state: 'loading' | 'ready' | 'failed';
  retry: () => void;
}

/**
 * Сховище, де лежить журнал: профіль-власник, а якщо там порожньо — той профіль,
 * у якому простір колись записано (до власників ключем був активний профіль).
 */
export function vaultOf(chatId: string, home: string, candidates: readonly string[]): string {
  if (spaceLog(home, chatId).length) return home;
  return candidates.find((id) => id && id !== home && spaceLog(id, chatId).length > 0) ?? home;
}

/** Простір гілки `chatId`: журнал з мосту в сховище профілю, стан — згортка журналу. */
export function useSpace(chatId: string): SpaceView {
  // Журнал простору живе в профілі, якому належить його розмова (SPEC-2810 §2), а не в активному.
  const activeId = useProfiles((s) => s.activeId);
  const profiles = useProfiles((s) => s.profiles);
  const owner = useChatProfiles((s) => ownerOf(chatId, s.map, profiles));
  const home = owner || activeId;
  const tick = useSpaceNav((s) => s.tick);
  const profileId = useMemo(
    () => vaultOf(chatId, home, [activeId, ...profiles.map((p) => p.id)]),
    [chatId, home, activeId, profiles, tick],
  );
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const fallbackMe = useMessengerStore((s) => s.currentUser.id);
  const [state, setState] = useState<SpaceView['state']>('loading');
  const [attempt, setAttempt] = useState(0);
  const needMembers = Boolean(chat && !chat.members?.length);

  const needLines = Boolean(chat && !chat.messages.length);
  useEffect(() => {
    if (needLines) void Promise.resolve(useMessengerStore.getState().loadMessagesForChat(chatId)).catch(() => undefined);
  }, [needLines, chatId]);

  useEffect(() => {
    if (needMembers) void Promise.resolve(useMessengerStore.getState().loadGroupMembers(chatId)).catch(() => undefined);
  }, [needMembers, chatId]);

  useEffect(() => {
    let alive = true;
    setState('loading');
    pullSpace(home, chatId)
      .then(() => {
        if (!alive) return;
        setState('ready');
        useSpaceNav.getState().bump();
      })
      .catch(() => alive && setState('failed'));
    return () => {
      alive = false;
    };
  }, [home, chatId, attempt]);

  const space = useMemo(() => foldSpace(spaceLog(profileId, chatId)).space, [profileId, chatId, tick]);
  const meId = chat?.members?.find((m) => m.isMe)?.id ?? fallbackMe;
  return { chat, space, meId, profileId, state, retry: () => setAttempt((n) => n + 1) };
}
