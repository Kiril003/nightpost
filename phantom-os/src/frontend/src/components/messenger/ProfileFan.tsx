import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronDown, SlidersHorizontal } from 'lucide-react';
import { FormSheet } from './FormSheet';
import { createPortal } from 'react-dom';
import { avatarLetters } from './kit/Avatar';
import { ProfileGlyphIcon } from './ProfileLookEditor';
import {
  glyphForProfile,
  openProfilesSheet,
  profileToneIndex,
  useProfiles,
  type Profile,
} from '../../stores/profilesStore';
import { useUIStore } from '../../stores/uiStore';
import { useChatProfiles } from '../../stores/chatProfileStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { unreadByProfile } from '../../services/profileScope';
import { useTranslation } from '../../i18n/useTranslation';
import { haptic } from '../../utils/haptic';

export const FAN_HOLD_MS = 320;

/** Профілі (крім активного), де є непрочитане. Рядок, щоб селектор не смикав рендер без змін. */
function useUnreadElsewhere(): Set<string> {
  const key = useMessengerStore((m) => {
    const { profiles, activeId } = useProfiles.getState();
    const counts = unreadByProfile(m.chats, useChatProfiles.getState().map, profiles);
    return Object.keys(counts).filter((id) => id !== activeId).sort().join(' ');
  });
  // Перерахунок і тоді, коли змінилися власники чи активний профіль, а розмови — ні.
  useChatProfiles((s) => s.map);
  useProfiles((s) => s.activeId);
  return new Set(key ? key.split(' ') : []);
}

const UnreadDot: React.FC<{ className: string }> = ({ className }) => (
  <span aria-hidden="true" data-profile-dot className={`absolute w-2 h-2 rounded-full bg-accent ring-2 ring-canvas ${className}`} />
);
const SLOP_PX = 10;
const ITEM_W = 72;
const GUTTER = 16;

type Spot = { left: number; top: number };

export const ProfileFan: React.FC<{
  onTap?: () => void;
  className?: string;
  children: React.ReactNode;
}> = ({ onTap, className = '', children }) => {
  const { t } = useTranslation();
  const anchor = useRef<HTMLSpanElement>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const start = useRef<{ x: number; y: number } | null>(null);
  const held = useRef(false);
  const [choices, setChoices] = useState<Profile[] | null>(null);
  const [spot, setSpot] = useState<Spot>({ left: GUTTER, top: 0 });
  const [hot, setHot] = useState<string | null>(null);
  const hotRef = useRef<string | null>(null);

  const clear = () => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = null;
  };
  useEffect(() => clear, []);

  const close = () => {
    setChoices(null);
    setHot(null);
    hotRef.current = null;
  };

  const open = () => {
    timer.current = null;
    const list = useProfiles.getState().fanChoices(3);
    if (list.length === 0) return;
    held.current = true;
    const r = anchor.current?.getBoundingClientRect();
    const vw = typeof window !== 'undefined' ? window.innerWidth || 390 : 390;
    const width = list.length * ITEM_W;
    const left = Math.max(GUTTER, Math.min((r?.left ?? GUTTER) + (r?.width ?? 0) / 2 - width / 2, vw - width - GUTTER));
    setSpot({ left, top: (r?.bottom ?? 0) + 8 });
    setChoices(list);
    haptic('long');
  };

  const aim = (x: number, y: number) => {
    const hit = document.elementFromPoint?.(x, y)?.closest?.('[data-fan-id]');
    const id = hit?.getAttribute('data-fan-id') ?? null;
    if (id !== hotRef.current) {
      hotRef.current = id;
      setHot(id);
      if (id) haptic('tick');
    }
  };

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== undefined && e.button > 0) return;
    held.current = false;
    start.current = { x: e.clientX ?? 0, y: e.clientY ?? 0 };
    clear();
    timer.current = setTimeout(open, FAN_HOLD_MS);
    try {
      anchor.current?.setPointerCapture?.(e.pointerId);
    } catch {
      // Захоплення недоступне — віяло працює і без нього.
    }
  };

  const onPointerMove = (e: React.PointerEvent) => {
    const x = e.clientX ?? 0;
    const y = e.clientY ?? 0;
    if (timer.current && start.current && Math.hypot(x - start.current.x, y - start.current.y) > SLOP_PX) {
      clear();
      return;
    }
    if (choices) aim(x, y);
  };

  const onPointerUp = (e: React.PointerEvent) => {
    clear();
    if (!choices) return;
    aim(e.clientX ?? 0, e.clientY ?? 0);
    const id = hotRef.current;
    const picked = id ? choices.find((p) => p.id === id) : undefined;
    close();
    if (!picked) return;
    if (useProfiles.getState().setActive(picked.id)) {
      haptic('confirm');
      useUIStore.getState().toast({ kind: 'info', message: picked.name });
    }
  };

  const onPointerCancel = () => {
    clear();
    close();
  };

  const profiles = useProfiles((s) => s.profiles);
  const unread = useUnreadElsewhere();
  const photos = useMessengerStore((m) => m.profilePhotos);

  return (
    <span
      ref={anchor}
      data-profile-fan-anchor
      className={`inline-flex touch-none select-none ${className}`}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onContextMenu={(e) => e.preventDefault()}
      onClickCapture={(e) => {
        if (held.current) {
          held.current = false;
          e.preventDefault();
          e.stopPropagation();
          return;
        }
        if (onTap) {
          e.preventDefault();
          e.stopPropagation();
          onTap();
        }
      }}
    >
      {children}
      {choices &&
        typeof document !== 'undefined' &&
        createPortal(
          <div
            data-testid="profile-fan"
            role="menu"
            aria-label={t('profiles.title')}
            style={{ left: spot.left, top: spot.top }}
            className="fixed z-[1400] flex rounded-sheet bg-raised border-[0.5px] border-line py-2"
          >
            {choices.map((p) => {
              const tone = profileToneIndex(profiles, p.id);
              const isHot = hot === p.id;
              return (
                <div
                  key={p.id}
                  role="menuitem"
                  data-fan-id={p.id}
                  data-hot={isHot ? 'true' : undefined}
                  aria-label={unread.has(p.id) ? `${p.name}, ${t('profileDot.here')}` : p.name}
                  className="w-[72px] flex flex-col items-center gap-1 px-1"
                >
                  <span
                    className={`relative w-12 h-12 rounded-full flex items-center justify-center text-headline font-semibold transition-transform duration-instant ease-standard ${isHot ? 'scale-110' : ''}`}
                    style={{
                      background: `var(--ph-person-${tone})`,
                      color: `var(--ph-person-${tone}-ink)`,
                      outline: isHot ? `2px solid var(--ph-person-${tone}-name)` : undefined,
                      outlineOffset: 2,
                    }}
                  >
                    {photos[p.id] ? (
                      <img src={photos[p.id]} alt="" className="absolute inset-0 w-full h-full rounded-full object-cover" />
                    ) : (
                      avatarLetters(p.name)
                    )}
                    {unread.has(p.id) && <UnreadDot className="top-0 right-0" />}
                  </span>
                  <span className={`max-w-full truncate text-caption ${isHot ? 'text-ink' : 'text-ink-2'}`}>{p.name}</span>
                </div>
              );
            })}
          </div>,
          document.body,
        )}
    </span>
  );
};

export const ChatsProfileTitle: React.FC<{ fallback: string; slot?: boolean }> = ({ fallback, slot }) => {
  const { t } = useTranslation();
  const profiles = useProfiles((s) => s.profiles);
  const activeId = useProfiles((s) => s.activeId);
  const setActive = useProfiles((s) => s.setActive);
  const chats = useMessengerStore((m) => m.chats);
  const photos = useMessengerStore((m) => m.profilePhotos);
  const owners = useChatProfiles((s) => s.map);
  const [open, setOpen] = useState(false);
  const counts = useMemo(() => unreadByProfile(chats, owners, profiles), [chats, owners, profiles]);
  const active = profiles.find((p) => p.id === activeId) ?? profiles[0];
  if (profiles.length < 2 || !active) return <>{fallback}</>;
  const elsewhere = profiles.reduce((n, p) => (p.id === active.id ? n : n + (counts[p.id] ?? 0)), 0);
  return (
    <>
      <button
        type="button"
        data-testid="chats-profile-title"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-label={`${t('profiles.title')}: ${active.name}${elsewhere > 0 ? `, ${t('profileDot.elsewhere')}` : ''}`}
        onClick={() => {
          haptic('tap');
          setOpen(true);
        }}
        className={slot ? 'relative shrink-0 w-11 h-11 flex items-center justify-center active:opacity-70' : 'touch-floor-inline max-w-full min-w-0 flex items-center gap-2 text-left active:opacity-70'}
      >
        {slot ? (
          <>
            <span aria-hidden data-accent={active.look?.accent ?? undefined} data-testid="chats-profile-dot" className="w-8 h-8 rounded-full bg-accent-soft text-accent-text flex items-center justify-center">
              <ProfileGlyphIcon glyph={glyphForProfile(active)} className="w-4 h-4" />
            </span>
            {elsewhere > 0 && (
              <span data-testid="chats-profile-elsewhere" className="absolute top-0.5 right-0 min-w-[18px] h-[18px] px-1 rounded-full bg-ink-2 text-canvas text-xs leading-[18px] font-semibold tnum text-center">
                {elsewhere > 99 ? '99+' : elsewhere}
              </span>
            )}
          </>
        ) : (
          <>
            <span aria-hidden data-accent={active.look?.accent ?? undefined} data-testid="chats-profile-dot" className="shrink-0 w-2.5 h-2.5 rounded-full bg-accent" />
            <span className="min-w-0 truncate">{active.name}</span>
            <ChevronDown aria-hidden className="shrink-0 w-5 h-5 text-ink-2" strokeWidth={1.75} />
            {elsewhere > 0 && (
              <span data-testid="chats-profile-elsewhere" className="shrink-0 min-w-5 h-5 px-1.5 rounded-full bg-overlay text-ink-2 text-caption font-semibold tnum flex items-center justify-center">
                {elsewhere > 99 ? '99+' : elsewhere}
              </span>
            )}
          </>
        )}
      </button>
      <FormSheet open={open} onClose={() => setOpen(false)} title={t('profiles.title')} testId="chats-profile-sheet">
        <div className="pb-2 flex flex-col">
          {profiles.map((p) => {
            const tone = profileToneIndex(profiles, p.id);
            const current = p.id === active.id;
            const n = current ? 0 : counts[p.id] ?? 0;
            return (
              <button
                key={p.id}
                type="button"
                data-testid={`chats-profile-pick-${p.id}`}
                aria-current={current ? 'true' : undefined}
                onClick={() => {
                  haptic('tap');
                  if (!current) setActive(p.id);
                  setOpen(false);
                }}
                className="min-h-14 px-4 py-2 flex items-center gap-3 text-left active:bg-overlay"
              >
                <span
                  className="relative shrink-0 w-10 h-10 rounded-full flex items-center justify-center text-subhead font-semibold overflow-hidden"
                  style={{ background: `var(--ph-person-${tone})`, color: `var(--ph-person-${tone}-ink)` }}
                >
                  {photos[p.id] ? <img src={photos[p.id]} alt="" className="absolute inset-0 w-full h-full object-cover" /> : avatarLetters(p.name)}
                </span>
                <span className="min-w-0 flex-1 truncate text-body font-semibold text-ink">{p.name}</span>
                {current ? (
                  <span className="shrink-0 text-footnote text-ink-2">{t('profiles.now')}</span>
                ) : n > 0 ? (
                  <span className="shrink-0 text-footnote font-semibold text-ink-2 tnum">{t('profiles.unreadCount', { count: n })}</span>
                ) : null}
              </button>
            );
          })}
          <button
            type="button"
            data-testid="chats-profile-manage"
            onClick={() => {
              haptic('tap');
              setOpen(false);
              openProfilesSheet();
            }}
            className="min-h-14 mt-1 px-4 flex items-center gap-3 text-left border-t border-line active:bg-overlay"
          >
            <span className="shrink-0 w-10 h-10 rounded-full bg-raised text-ink flex items-center justify-center">
              <SlidersHorizontal aria-hidden className="w-5 h-5" strokeWidth={1.75} />
            </span>
            <span className="flex-1 text-body text-ink">{t('profiles.manage')}</span>
          </button>
        </div>
      </FormSheet>
    </>
  );
};
