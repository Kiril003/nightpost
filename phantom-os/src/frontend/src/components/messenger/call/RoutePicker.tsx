/**
 * Вибір, куди йде звук: телефон біля вуха, динамік, навушники. Кнопка в
 * ряду керування показує ПОТОЧНИЙ вихід (так, як його назвав апарат); дотик
 * відкриває коротке меню над рядом. Вибір — один POST до моста; що він
 * відповів, те й показано (`useAudioRoutes`). Без моста цієї кнопки немає
 * зовсім — картка ставить на її місце стару кнопку «Звук».
 */

import React, { useEffect, useRef, useState } from 'react';
import { Bluetooth, Check, Ear, Headphones, Volume2 } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { MessageKey } from '../../../i18n';
import { soundFx } from '../../../utils/messengerSound';
import type { AudioRoute, AudioRouteKind, AudioRoutes } from './audioRoute';
import { CallButton } from './CallButton';
import { useRise } from './motion';

const ICON: Record<AudioRouteKind, LucideIcon> = {
  earpiece: Ear,
  speaker: Volume2,
  bluetooth: Bluetooth,
  wired: Headphones,
};

const KIND_KEY: Record<AudioRouteKind, MessageKey> = {
  earpiece: 'call.route.earpiece',
  speaker: 'call.route.speaker',
  bluetooth: 'call.route.bluetooth',
  wired: 'call.route.wired',
};

export const RoutePicker: React.FC<{
  routes: AudioRoutes;
  busy: boolean;
  onChoose: (id: string) => void;
  onOpen: () => void;
}> = ({ routes, busy, onChoose, onOpen }) => {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);
  useRise(menuRef, open, 10, 200);

  const nameOf = (r: AudioRoute): string => r.name ?? t(KIND_KEY[r.kind]);
  const current = routes.routes.find((r) => r.id === routes.current) ?? null;
  const Icon = current ? ICON[current.kind] : Volume2;
  const label = current ? nameOf(current) : t('call.route.menu');

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  return (
    <div className="relative">
      <CallButton
        onClick={() => {
          soundFx.playTap();
          if (!open) onOpen();
          setOpen((v) => !v);
        }}
        label={label}
        hint={t('call.route.button', { route: label })}
        tone={current && current.kind !== 'earpiece' ? 'on' : 'idle'}
        size="md"
        disabled={busy}
        data-call-route={routes.current ?? ''}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <Icon className="w-7 h-7" aria-hidden />
      </CallButton>
      {open && (
        <>
          {/* Дотик повз меню — закрити. Невидимий шар, не затемнення. */}
          <div className="fixed inset-0 z-30" aria-hidden onClick={() => setOpen(false)} />
          <div
            ref={menuRef}
            role="menu"
            aria-label={t('call.route.menu')}
            data-call-route-menu
            className="absolute left-0 bottom-full mb-3 z-40 min-w-[220px] max-w-[min(300px,calc(100vw-32px))] p-1.5 rounded-sheet"
            style={{ background: 'var(--ph-surface)', border: '1px solid var(--ph-line-strong)' }}
          >
            {routes.routes.map((r) => {
              const ItemIcon = ICON[r.kind];
              const on = r.id === routes.current;
              return (
                <button
                  key={r.id}
                  type="button"
                  role="menuitemradio"
                  aria-checked={on}
                  onClick={() => {
                    soundFx.playTap();
                    setOpen(false);
                    if (!on) onChoose(r.id);
                  }}
                  className="w-full min-h-[48px] flex items-center gap-3 px-3 rounded-control text-left text-ink hover:bg-raised transition-colors duration-instant"
                >
                  <ItemIcon className="w-5 h-5 shrink-0 text-ink-2" aria-hidden />
                  <span className="flex-1 min-w-0 truncate text-body">{nameOf(r)}</span>
                  {on && <Check className="w-5 h-5 shrink-0 text-accent" aria-hidden />}
                </button>
              );
            })}
          </div>
        </>
      )}
    </div>
  );
};
