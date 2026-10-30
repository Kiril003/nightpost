import React from 'react';
import { scrimFor, useLook, wallpaperUrl } from '../../services/appearance';
import { resolveLook, useRingPresets } from '../../services/ringPresets';
import { useMessengerStore } from '../../stores/messengerStore';
import { isPhoneShell } from './phoneShell';

/**
 * Тло розмови під стрічкою. Приглушення — завіса кольору полотна, тож бульбашки
 * (суцільні `self`/`surface`) і рядки на табличках `ph-on-stage` читаються на будь-якому фото.
 * День і Ніч мають свої значення; яке діє, вирішує CSS за `data-theme`, без JS.
 * На ПК панель розмови втричі ширша за телефон: там візерунок крупніший і тихіший.
 *
 * Поки людина нічого не обирала, під стрічкою стіна D (`--ph-wall` і точки `--ph-wall-dot`),
 * а не рівне полотно. Свідомо обране «Без тла» лишається рівним.
 */
const useChoice = (chatId: string) => {
  const look = useLook((s) => s.look);
  const presets = useRingPresets((s) => s.presets);
  const chat = useMessengerStore((s) => s.chats.find((c) => c.id === chatId));
  const choice = resolveLook(chat ?? { id: chatId }, look, presets).wallpaper;
  const wall = choice.kind === 'none' && choice === look.wallpaper && !look.wallpaperPicked;
  return { look, choice, wall };
};

export const ChatWallpaper: React.FC<{ chatId: string }> = ({ chatId }) => {
  const { look, choice, wall } = useChoice(chatId);
  if (wall) {
    return (
      <div data-testid="chat-wallpaper" data-kind="wall" className="ph-wall" aria-hidden="true">
        <div className="ph-wall-dots" />
      </div>
    );
  }
  const url = wallpaperUrl(choice);
  if (!url) return null;
  const style = {
    '--wp-dim-day': String(scrimFor(look.dim.day, choice.kind)),
    '--wp-dim-night': String(scrimFor(look.dim.night, choice.kind)),
    '--wp-blur-day': `${look.blur.day}px`,
    '--wp-blur-night': `${look.blur.night}px`,
  } as React.CSSProperties;
  return (
    <div data-testid="chat-wallpaper" data-kind={choice.kind} data-surface={__PHONE_BUILD__ || isPhoneShell() ? 'phone' : 'desk'} className="ph-wallpaper" style={style} aria-hidden="true">
      <div
        className={`ph-wallpaper-img ${choice.kind === 'builtin' ? 'ph-wallpaper-tile' : 'ph-wallpaper-cover'}`}
        style={{ backgroundImage: `url("${url}")` }}
      />
      <div className="ph-wallpaper-scrim" />
    </div>
  );
};

export const useHasWallpaper = (chatId: string): boolean => {
  const { choice, wall } = useChoice(chatId);
  return wall || choice.kind !== 'none';
};
