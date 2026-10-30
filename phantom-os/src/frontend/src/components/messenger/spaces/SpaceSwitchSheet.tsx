import React, { useMemo } from 'react';
import { Check, Plus } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { people as memberWord } from '../GroupProfileSheet';
import { useMessengerStore } from '../../../stores/messengerStore';
import { foldSpace } from '../../../services/spaces/spaceLog';
import { spaceLog } from '../../../services/spaces/spaceVault';
import { liveMembers } from '../../../services/spaces/spaceRooms';
import { isSpaceChat, useSpaceNav } from '../../../services/spaces/spaceNav';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../kit/Avatar';

/**
 * Перемикання (sv-switch): усі гілки роду `space` і «Створити простір». Журнал
 * простору, якого ще не відкривали, порожній — тоді назва й лік з самої гілки.
 */
export const SpaceSwitchSheet: React.FC<{ open: boolean; current: string; profileId: string; onClose: () => void }> = ({ open, current, profileId, onClose }) => {
  const { t, locale } = useTranslation();
  const tick = useSpaceNav((s) => s.tick);
  const chats = useMessengerStore((s) => s.chats);
  const list = useMemo(() => {
    if (!open) return [];
    return chats.filter(isSpaceChat).map((c) => {
      const sp = foldSpace(spaceLog(profileId, c.id)).space;
      const n = sp ? liveMembers(sp).length : c.membersCount ?? (c.members?.length ? c.members.length : null);
      const kind = sp ? t(`space.tpl.${sp.template}`) : t('space.kind');
      const sub = n !== null ? `${kind} · ${n} ${memberWord(n, t, locale)}` : kind;
      return { id: c.id, name: sp?.name ?? c.title, sub, unread: Math.max(0, c.unreadCount ?? 0) };
    });
  }, [open, chats, profileId, tick, t]);
  const go = (fn: () => void) => {
    onClose();
    fn();
  };
  return (
    <FormSheet open={open} onClose={onClose} title={t('space.switch.title')} testId="space-switch-sheet">
      <ul>
        {list.map((s) => (
          <li key={s.id}>
            <button
              type="button"
              data-testid={`space-pick-${s.id}`}
              onClick={() => go(() => s.id !== current && useSpaceNav.getState().swap({ s: 'home', chatId: s.id }))}
              className="w-full min-h-[64px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
            >
              <Avatar name={s.name} tone={s.id} radius="rounded-full" className="w-10 h-10" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-headline text-ink">{s.name}</span>
                <span data-testid={`space-pick-sub-${s.id}`} className="block truncate text-subhead text-ink-2">{s.sub}</span>
              </span>
              {s.id === current ? (
                <Check className="w-5 h-5 shrink-0 text-ink" strokeWidth={2} aria-label={s.name} />
              ) : s.unread > 0 ? (
                <span
                  data-testid={`space-pick-unread-${s.id}`}
                  className="shrink-0 min-w-[22px] h-[22px] px-2 rounded-full bg-overlay text-ink text-caption font-semibold tnum flex items-center justify-center"
                >
                  {s.unread}
                </span>
              ) : null}
            </button>
          </li>
        ))}
        <li>
          <button
            type="button"
            data-testid="space-create-door"
            onClick={() => go(() => useSpaceNav.getState().push({ s: 'create' }))}
            className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
          >
            <span className="w-10 h-10 shrink-0 rounded-full bg-overlay text-ink flex items-center justify-center">
              <Plus className="w-5 h-5" strokeWidth={1.75} aria-hidden />
            </span>
            <span className="text-headline text-ink">{t('space.create')}</span>
          </button>
        </li>
      </ul>
    </FormSheet>
  );
};
