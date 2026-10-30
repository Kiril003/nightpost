/** Кола (np 2.2): людина чи група може стояти в кількох колах, ставиться тут, живе на телефоні. */
import React, { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import type { Chat, ChatCircle } from '../../types/messenger';
import { useTranslation } from '../../i18n/useTranslation';
import { useMessengerStore } from '../../stores/messengerStore';
import { isWider, reachOf, type RingPeer, type RingRow } from '../../services/ringDowngrade';
import { chatKind } from './chatListFormat';
import { circleName, useCirclesStore } from '../../stores/circlesStore';
import { DoorRow } from './FormSheet';
import { RingDowngradeSheet } from './RingDowngradeSheet';
import { CIRCLES, CIRCLE_KEY, isRealCircle } from './todayFormat';

/** Коло мають лише люди й групи — не помічник, не «Збережене», не свій пристрій. */
export const canHaveCircle = (chat: Chat): boolean => chatKind(chat) !== 'other' && !chat.mine;

export const CircleChoices: React.FC<{
  current: ChatCircle;
  busy?: boolean;
  peer?: RingPeer;
  onPick: (c: ChatCircle) => void;
}> = ({ current, busy = false, peer, onPick }) => {
  const { t } = useTranslation();
  const [rows, setRows] = useState<RingRow[] | null>(null);
  const now: ChatCircle = isRealCircle(current) ? current : 'all';
  const choices: Array<{ key: ChatCircle; label: string }> = [
    { key: 'all', label: t('circles.none') },
    ...CIRCLES.map((c) => ({ key: c, label: t(CIRCLE_KEY[c]) })),
  ];
  return (
    <>
      <div data-testid="circle-choices" role="group" aria-label={t('circles.who')} className="pb-2">
        {choices.map((c) => (
          <DoorRow
            key={c.key}
            label={c.label}
            onClick={() => {
              if (!busy && c.key !== now) {
                onPick(c.key);
                if (peer && isWider(now, c.key)) {
                  void reachOf(peer).then((found) => {
                    if (found.length > 0) setRows(found);
                  });
                }
              }
            }}
            trailing={c.key === now ? <Check className="w-5 h-5 shrink-0 text-ink" strokeWidth={1.75} aria-label={t('phoneChatMenu.selected')} /> : undefined}
          />
        ))}
      </div>
      {peer && rows && rows.length > 0 && (
        <RingDowngradeSheet peer={peer} rows={rows} open onClose={() => setRows(null)} />
      )}
    </>
  );
};

/** Назви кіл розмови; невідомі власні кола довантажуються. */
export const useCircleLabels = (chatId: string): string[] => {
  const { t } = useTranslation();
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const known = useCirclesStore((s) => s.circles);
  const ids: string[] = chat?.circles
    ? chat.circles
    : chat?.circle && isRealCircle(chat.circle)
      ? [chat.circle]
      : [];

  const unresolved = ids.some((id) => !isRealCircle(id as ChatCircle) && !known.some((c) => c.id === id));
  useEffect(() => {
    if (unresolved) void useCirclesStore.getState().load();
  }, [unresolved]);

  return ids.flatMap((id) => {
    const found = known.find((c) => c.id === id);
    if (!found && !isRealCircle(id as ChatCircle)) return [];
    return [circleName(found ?? { id, name: null }, t)];
  });
};

