import React, { useMemo, useState } from 'react';
import { Bookmark, Check, Search, Users, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { Chat } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { Avatar } from '../Avatar';
import { displayTitle } from '../chatListFormat';
import { firstName, matchRecipient, recipientCandidates, recipientKind } from '../../../services/recipients';

const ICON = { strokeWidth: 1.75 } as const;

export const RecipientAvatar: React.FC<{ chat: Chat; on: boolean; size?: string }> = ({ chat, on, size = 'w-12 h-12 text-headline' }) => {
  const kind = recipientKind(chat);
  return (
    <span className="relative shrink-0">
      <Avatar
        src={chat.avatar}
        name={displayTitle(chat.title)}
        tone={chat.peerNodeId || chat.id}
        radius={kind === 'person' ? 'rounded-full' : 'rounded-control'}
        className={size}
      />
      {on && (
        <span
          data-pick-check
          aria-hidden
          className="absolute -right-0.5 -bottom-0.5 w-5 h-5 rounded-full bg-ink text-canvas flex items-center justify-center border-2 border-canvas"
        >
          <Check className="w-3 h-3" strokeWidth={3} />
        </span>
      )}
    </span>
  );
};

/** Вибрані — чипами; дотик «×» прибирає. */
export const RecipientChips: React.FC<{ chats: Chat[]; selected: string[]; onChange: (ids: string[]) => void }> = ({ chats, selected, onChange }) => {
  const { t } = useTranslation();
  const byId = useMemo(() => new Map(chats.map((c) => [c.id, c])), [chats]);
  const picked = selected.map((id) => byId.get(id)).filter((c): c is Chat => Boolean(c));
  if (!picked.length) return null;
  return (
    <div data-pick-chips className="flex items-center gap-1.5 overflow-x-auto no-scrollbar px-4 py-1">
      {picked.map((c) => {
        const name = firstName(c) || displayTitle(c.title);
        return (
          <span key={c.id} data-pick-chip={c.id} className="shrink-0 h-8 pl-1 pr-0.5 rounded-chip bg-overlay text-ink inline-flex items-center gap-1.5">
            <Avatar src={c.avatar} name={displayTitle(c.title)} tone={c.peerNodeId || c.id} className="w-6 h-6 text-caption" />
            <span className="text-subhead max-w-[120px] truncate">{name}</span>
            <button
              type="button"
              aria-label={t('pick.remove', { name })}
              onClick={() => onChange(selected.filter((id) => id !== c.id))}
              className="touch-floor-inline w-7 h-7 rounded-full flex items-center justify-center text-ink-3 hover:text-ink"
            >
              <X className="w-4 h-4" {...ICON} />
            </button>
          </span>
        );
      })}
    </div>
  );
};

interface PickerProps {
  chats: Chat[];
  selected: string[];
  onChange: (ids: string[]) => void;
  /** Розмова, з якої пересилаємо: туди ж не шлемо. */
  exclude?: string;
}

/** Збережене — першим, далі той самий порядок, що й у кандидатів. */
export const gridCandidates = (chats: Chat[], exclude?: string): Chat[] => {
  const all = recipientCandidates(chats, exclude);
  return [...all.filter((c) => recipientKind(c) === 'saved'), ...all.filter((c) => recipientKind(c) !== 'saved')];
};

const RING = 'outline outline-2 outline-offset-2 outline-accent-line';

const TileLead: React.FC<{ chat: Chat; on: boolean }> = ({ chat, on }) => {
  const kind = recipientKind(chat);
  const ring = on ? RING : '';
  if (kind === 'saved' || ((kind === 'group' || kind === 'channel') && !chat.avatar)) {
    const Icon = kind === 'saved' ? Bookmark : Users;
    return (
      <span
        data-pick-icon={kind}
        data-pick-ring={on || undefined}
        className={`w-14 h-14 rounded-full bg-raised text-ink-2 flex items-center justify-center ${ring}`}
      >
        <Icon className="w-6 h-6" {...ICON} aria-hidden />
      </span>
    );
  }
  return (
    <span data-pick-ring={on || undefined} className={`block rounded-full ${ring}`}>
      <Avatar src={chat.avatar} name={displayTitle(chat.title)} tone={chat.peerNodeId || chat.id} radius="rounded-full" className="w-14 h-14 text-title-2" />
    </span>
  );
};

/**
 * Сітка адресатів (аркуш «Надіслати», вибір розмов у списку): пошук і чотири
 * колонки, Збережене першим. Дотик — позначка й кільце; пошук лише звужує
 * сітку, вибране живе поза нею.
 */
export const RecipientPicker: React.FC<PickerProps> = ({ chats, selected, onChange, exclude }) => {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const all = useMemo(() => gridCandidates(chats, exclude), [chats, exclude]);
  const shown = all.filter((c) => matchRecipient(c, query));
  const chosen = new Set(selected);
  const toggle = (id: string) => {
    soundFx.playTap();
    onChange(chosen.has(id) ? selected.filter((x) => x !== id) : [...selected, id]);
  };

  return (
    <div data-recipient-picker>
      <div className="mx-4 my-1 relative">
        <Search className="w-5 h-5 absolute left-3.5 top-1/2 -translate-y-1/2 text-ink-3" {...ICON} aria-hidden />
        <input
          type="text"
          data-pick-search
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder={t('share.search')}
          aria-label={t('share.search')}
          className="w-full h-12 pl-11 pr-4 rounded-full bg-surface border border-line text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
        />
      </div>
      {shown.length === 0 ? (
        <p className="px-4 py-4 text-callout text-ink-3">{t('pick.none')}</p>
      ) : (
        <ul data-pick-grid className="grid grid-cols-4 gap-y-3.5 px-2 pt-3 pb-2">
          {shown.map((c) => {
            const on = chosen.has(c.id);
            const kind = recipientKind(c);
            const name = kind === 'saved' ? t('chatList.saved') : kind === 'person' ? firstName(c) || displayTitle(c.title) : displayTitle(c.title);
            return (
              <li key={c.id} className="min-w-0">
                <button
                  type="button"
                  data-pick-row={c.id}
                  aria-pressed={on}
                  onClick={() => toggle(c.id)}
                  className="w-full min-w-0 py-1 flex flex-col items-center gap-1.5 rounded-control active:bg-overlay select-none"
                >
                  <span className="relative">
                    <TileLead chat={c} on={on} />
                    {on && (
                      <span
                        data-pick-check
                        aria-hidden
                        className="absolute -right-0.5 -bottom-0.5 w-6 h-6 rounded-full bg-accent text-on-accent border-2 border-canvas flex items-center justify-center"
                      >
                        <Check className="w-3.5 h-3.5" strokeWidth={2.75} />
                      </span>
                    )}
                  </span>
                  <span className={`max-w-full px-1 truncate text-footnote ${on ? 'text-ink' : 'text-ink-2'}`}>{name}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
};
