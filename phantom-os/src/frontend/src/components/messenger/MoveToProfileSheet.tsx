/**
 * S5: вибір профілю-власника розмови (крок аркуша довгого дотику).
 * Дотик по іншому профілю переносить розмову: вона зникає зі старого списку, міст отримує нову мапу.
 */
import React, { useMemo } from 'react';
import { Check } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import { pluralKey } from '../chat/tNow';
import { ProfileGlyphIcon } from './ProfileLookEditor';
import { DoorRow } from './FormSheet';
import { ownable, ownerOf, useChatProfiles } from '../../stores/chatProfileStore';
import { glyphForProfile, useProfiles } from '../../stores/profilesStore';
import { useMessengerStore } from '../../stores/messengerStore';
import type { Chat } from '../../types/messenger';

export const canMoveToProfile = (chat: Chat, profileCount: number): boolean => profileCount > 1 && ownable(chat);

export const MoveToProfileSheet: React.FC<{ chat: Chat; onDone: () => void }> = ({ chat, onDone }) => {
  const { t, locale } = useTranslation();
  const profiles = useProfiles((s) => s.profiles);
  const map = useChatProfiles((s) => s.map);
  const chats = useMessengerStore((s) => s.chats);
  const current = ownerOf(chat.id, map, profiles);
  const counts = useMemo(() => {
    const n: Record<string, number> = {};
    for (const c of chats) {
      if (c.archived || !ownable(c)) continue;
      const id = ownerOf(c.id, map, profiles);
      n[id] = (n[id] ?? 0) + 1;
    }
    return n;
  }, [chats, map, profiles]);

  return (
    <div data-testid="move-to-profile" role="group" aria-label={t('profileMove.door')} className="pb-2">
      {profiles.map((p) => {
        const n = counts[p.id] ?? 0;
        return (
          <div key={p.id} data-testid={`move-to-${p.id}`}>
            <DoorRow
              icon={<ProfileGlyphIcon glyph={glyphForProfile(p)} className="w-5 h-5" />}
              label={p.name}
              hint={t(pluralKey(locale, n, 'profileMove.count'), { n })}
              onClick={() => {
                if (p.id !== current) useChatProfiles.getState().assign(chat.id, p.id);
                onDone();
              }}
              trailing={
                p.id === current ? (
                  <Check className="w-5 h-5 shrink-0 text-accent-text" strokeWidth={1.75} aria-label={t('phoneChatMenu.selected')} />
                ) : undefined
              }
            />
          </div>
        );
      })}
    </div>
  );
};

export default MoveToProfileSheet;
