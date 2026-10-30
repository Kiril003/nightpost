/** Моменти за профілем (SPEC-2810 §4, D5): аудиторія — люди профілю, свої дописи — у профілі, з якого їх виклали. */
import { useMemo } from 'react';
import { create } from 'zustand';
import type { Chat } from '../types/messenger';
import type { Moment } from './moments';
import { useProfiles, type Profile } from '../stores/profilesStore';
import { bridgeMaps, ownerOf, useChatProfiles } from '../stores/chatProfileStore';
import { useMessengerStore } from '../stores/messengerStore';

export const MOMENT_PROFILE_KEY = 'np.momentprofile.v1';

function load(): Record<string, string> {
  try {
    const raw = typeof localStorage === 'undefined' ? null : localStorage.getItem(MOMENT_PROFILE_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    const map = (parsed as { v?: unknown; map?: unknown } | null)?.map;
    if ((parsed as { v?: unknown } | null)?.v !== 1 || !map || typeof map !== 'object' || Array.isArray(map)) return {};
    return Object.fromEntries(Object.entries(map).filter(([k, v]) => k && typeof v === 'string' && v));
  } catch {
    return {};
  }
}

export const useMomentProfiles = create<{ map: Record<string, string>; record: (momentId: string, profileId: string) => void }>((set, get) => ({
  map: load(),
  record: (momentId, profileId) => {
    if (!momentId || !profileId || get().map[momentId] === profileId) return;
    const map = { ...get().map, [momentId]: profileId };
    set({ map });
    try {
      localStorage.setItem(MOMENT_PROFILE_KEY, JSON.stringify({ v: 1, map }));
    } catch {
      // Сховище недоступне: у памʼяті лишається.
    }
  },
}));

/** Людина належить профілю, що володіє розмовою з нею; без запису — основному, як і в мосту. */
export function ownedBy(peerProfile: Record<string, string>, profiles: Profile[], activeId: string): (peerId: string) => boolean {
  if (profiles.length <= 1) return () => true;
  return (peerId) => ownerOf(peerId, peerProfile, profiles) === activeId;
}

/** Свій допис видно лише в профілі, з якого його виклали; старі — в основному. Чужі — як були. */
export function mineHere(map: Record<string, string>, profiles: Profile[], activeId: string): (m: Pick<Moment, 'id' | 'mine'>) => boolean {
  if (profiles.length <= 1) return () => true;
  return (m) => !m.mine || ownerOf(m.id, map, profiles) === activeId;
}

const merged = (a: Chat[], b: Chat[]): Chat[] => {
  const ids = new Set(a.map((c) => c.id));
  return [...a, ...b.filter((c) => !ids.has(c.id))];
};

/** Хто з людей у профілі, що зараз активний. Мапа — з усіх розмов, не лише показаних. */
export function useOwnedPeers(chats: Chat[]): { owned: (peerId: string) => boolean; scoped: boolean } {
  const profiles = useProfiles((s) => s.profiles);
  const activeId = useProfiles((s) => s.activeId);
  const map = useChatProfiles((s) => s.map);
  const all = useMessengerStore((s) => s.chats);
  const meId = useMessengerStore((s) => s.currentUser?.id ?? '');
  return useMemo(() => {
    const { peerProfile } = bridgeMaps(merged(all, chats), map, profiles, meId);
    return { owned: ownedBy(peerProfile, profiles, activeId), scoped: profiles.length > 1 };
  }, [all, chats, map, profiles, activeId, meId]);
}

export function useMineHere(): (m: Pick<Moment, 'id' | 'mine'>) => boolean {
  const profiles = useProfiles((s) => s.profiles);
  const activeId = useProfiles((s) => s.activeId);
  const map = useMomentProfiles((s) => s.map);
  return useMemo(() => mineHere(map, profiles, activeId), [map, profiles, activeId]);
}

/** Що з моментів видно в активному профілі: чужі — лише від його людей, свої — лише викладені з нього. */
export function momentScope(owned: (peerId: string) => boolean, mine: (m: Pick<Moment, 'id' | 'mine'>) => boolean) {
  return (m: Pick<Moment, 'id' | 'mine' | 'fromId'>): boolean => (m.mine ? mine(m) : owned(m.fromId));
}

export function useMomentScope(chats: Chat[]): (m: Pick<Moment, 'id' | 'mine' | 'fromId'>) => boolean {
  const { owned } = useOwnedPeers(chats);
  const mine = useMineHere();
  return useMemo(() => momentScope(owned, mine), [owned, mine]);
}
