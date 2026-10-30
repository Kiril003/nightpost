/**
 * Дві смуги над стрічкою: закріплене і пошук у розмові. Розмітка — з ChatArea
 * без змін; винесено, щоб ChatArea лишився диригентом, а не простирадлом.
 */
import React, { useState } from 'react';
import { ChevronDown, ChevronUp, ChevronLeft, ChevronRight, CalendarDays, Pin, Search, X } from 'lucide-react';
import type { Chat, Message } from '../../../types/messenger';
import { useMessengerStore } from '../../../stores/messengerStore';
import { tNow } from '../../chat/tNow';
import { plainSnippet } from '../chatListFormat';
import { FormSheet } from '../FormSheet';
import { useWhenFlow } from '../when/WhenFlow';
interface PinnedBannerProps {
  pinned: Message[];
  index: number;
  onIndex: (next: number | ((prev: number) => number)) => void;
  onJump: (msgId: string) => void;
  onUnpin: (msgId: string) => void;
}

export const PinnedBanner: React.FC<PinnedBannerProps> = ({ pinned: pinnedMessages, index: currentPinnedIndex, onIndex, onJump, onUnpin }) => {
  const pinnedMsg = pinnedMessages[currentPinnedIndex] || pinnedMessages[0];
  if (!pinnedMsg) return null;
  return (
    <div className="px-3.5 py-1.5 bg-canvas border-b border-line flex items-center justify-between gap-3 shrink-0 z-10 text-ink">
      <div
        onClick={() => onJump(pinnedMsg.id)}
        className="flex items-center gap-2 min-w-0 cursor-pointer group flex-1"
      >
        <Pin className="w-3.5 h-3.5 text-ink-2 shrink-0" strokeWidth={2} />
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-caption font-semibold text-ink">
            <span>{tNow('feedBanners.pinnedOf', { n: Math.min(currentPinnedIndex + 1, pinnedMessages.length), total: pinnedMessages.length })}</span>
            <span className="text-caption text-ink-3 font-normal">
              {tNow('feedBanners.from', { name: pinnedMsg.senderName })}
            </span>
          </div>
          <p className="text-caption text-ink-3 truncate">
            {pinnedMsg.text ? plainSnippet(pinnedMsg.text) : (pinnedMsg.tableData?.title || pinnedMsg.type)}
          </p>
        </div>
      </div>

      <div className="flex items-center gap-1 shrink-0">
        {pinnedMessages.length > 1 && (
          <>
            <button
              onClick={() =>
                onIndex(
                  (prev) => (prev - 1 + pinnedMessages.length) % pinnedMessages.length
                )
              }
              className="touch-floor p-1 hover:bg-raised rounded-control text-ink-3 hover:text-ink transition-colors"
              title={tNow('feedBanners.prevPinned')}
            >
              <ChevronLeft className="w-3.5 h-3.5" strokeWidth={1.75} />
            </button>
            <button
              onClick={() =>
                onIndex((prev) => (prev + 1) % pinnedMessages.length)
              }
              className="touch-floor p-1 hover:bg-raised rounded-control text-ink-3 hover:text-ink transition-colors"
              title={tNow('feedBanners.nextPinned')}
            >
              <ChevronRight className="w-3.5 h-3.5" strokeWidth={1.75} />
            </button>
          </>
        )}

        <button
          onClick={() => onUnpin(pinnedMsg.id)}
          className="touch-floor p-1 text-ink-3 hover:text-ink hover:bg-raised rounded-control transition-colors"
          title={tNow('feedBanners.unpin')}
        >
          <X className="w-3.5 h-3.5" strokeWidth={1.75} />
        </button>
      </div>
    </div>
  );
};

interface ChatSearchBarProps {
  query: string;
  matchCount: number;
  matchIndex: number;
  onQuery: (q: string) => void;
  onPrev: () => void;
  onNext: () => void;
  onClose: () => void;
  /** Перейти до дня (YYYY-MM-DD); межі — перший і останній день розмови. */
  onJumpDate?: (day: string) => void;
  dateBounds?: { min: string; max: string } | null;
}

/** Перейти до дати: місяць зі спільних вікон «Коли» (msg-x) у межах розмови, без системного поля. */
const JumpDate: React.FC<{ bounds: { min: string; max: string }; onJump: (day: string) => void }> = ({ bounds, onJump }) => {
  const [open, setOpen] = useState(false);
  const flow = useWhenFlow({
    primaryKey: 'when.meetAt',
    allowPast: true,
    dayOnly: bounds,
    onPick: (r) => {
      setOpen(false);
      if (r.kind === 'at') onJump(r.date);
    },
  });
  return (
    <>
      <button
        type="button"
        data-testid="jump-date"
        onClick={() => {
          flow.open('date', { date: bounds.max });
          setOpen(true);
        }}
        className="touch-floor p-1 hover:bg-raised text-ink-3 hover:text-ink rounded-control transition-colors"
        title={tNow('msgr.jumpDate')}
        aria-label={tNow('msgr.jumpDate')}
      >
        <CalendarDays className="w-4 h-4" strokeWidth={1.75} />
      </button>
      {open && (
        <FormSheet open onClose={() => setOpen(false)} title={tNow('msgr.jumpDate')} testId="jump-date-sheet">
          {flow.body}
        </FormSheet>
      )}
    </>
  );
};

export const ChatSearchBar: React.FC<ChatSearchBarProps> = ({ query, matchCount, matchIndex, onQuery, onPrev, onNext, onClose, onJumpDate, dateBounds }) => {
  const inputRef = React.useRef<HTMLInputElement>(null);
  // Меню, що відкрило пошук, повертає фокус своїй кнопці вже ПІСЛЯ autoFocus — і на
  // телефоні клавіатура не зʼявлялась. Фокус береться ще раз, коли меню вже закрилось.
  React.useEffect(() => {
    const id = window.setTimeout(() => inputRef.current?.focus(), 0);
    return () => window.clearTimeout(id);
  }, []);
  return (
    <div className="px-3.5 py-1.5 bg-surface border-b border-line flex items-center justify-between gap-3 shrink-0 z-10 text-ink">
      <div className="flex items-center gap-2 flex-1 max-w-md">
        <Search className="w-4 h-4 text-ink-3" strokeWidth={1.75} />
        <input
          ref={inputRef}
          type="search"
          enterKeyHint="search"
          data-testid="chat-search-input"
          autoFocus
          value={query}
          onChange={(e) => {
            onQuery(e.target.value);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              if (e.shiftKey) {
                onPrev();
              } else {
                onNext();
              }
            } else if (e.key === 'Escape') {
              e.preventDefault();
              onQuery('');
              onClose();
            } else if (e.key === 'ArrowDown') {
              e.preventDefault();
              onNext();
            } else if (e.key === 'ArrowUp') {
              e.preventDefault();
              onPrev();
            }
          }}
          placeholder={tNow('phoneChatMenu.searchChat')}
          title={tNow('feedBanners.searchPlaceholder')}
          aria-label={tNow('phoneChatMenu.searchChat')}
          className="w-full px-3 py-1.5 bg-surface rounded-control text-body text-ink placeholder:text-ink-3 focus:outline-none [&::-webkit-search-cancel-button]:hidden"
        />
      </div>

      <div className="flex items-center gap-2 text-xs text-ink-3 shrink-0">
        {query && (
          <span>
            {matchCount > 0
              ? tNow('feedBanners.matchOf', { n: matchIndex + 1, total: matchCount })
              : tNow('feedBanners.notFound')}
          </span>
        )}
        {matchCount > 0 && (
          <>
            <button
              onClick={onPrev}
              className="touch-floor p-1 hover:bg-raised text-ink-3 hover:text-ink rounded-control transition-colors"
              title={tNow('feedBanners.prevMatch')}
            >
              <ChevronUp className="w-4 h-4" strokeWidth={1.75} />
            </button>
            <button
              onClick={onNext}
              className="touch-floor p-1 hover:bg-raised text-ink-3 hover:text-ink rounded-control transition-colors"
              title={tNow('feedBanners.nextMatch')}
            >
              <ChevronDown className="w-4 h-4" strokeWidth={1.75} />
            </button>
          </>
        )}
        {onJumpDate && dateBounds && <JumpDate bounds={dateBounds} onJump={onJumpDate} />}
        <button
          onClick={() => {
            onQuery('');
            onClose();
          }}
          className="touch-floor p-1 hover:bg-raised text-ink-3 hover:text-ink rounded-control transition-colors"
        >
          <X className="w-4 h-4" strokeWidth={1.75} />
        </button>
      </div>
    </div>
  );
};

/** Закріплене гілки — з дроту, одне на всіх; знімає той, кому вузол дозволив. */
export const GroupPinBar: React.FC<{ chat: Chat; onJump: (msgId: string) => void }> = ({ chat, onJump }) => {
  const [busy, setBusy] = React.useState(false);
  const pin = chat.pinnedMessage;
  if (!pin) return null;
  const unpin = async () => {
    setBusy(true);
    try {
      await useMessengerStore.getState().pinGroupMessage(chat.id, '');
    } catch {
      /* відмову з причиною покаже аркуш групи; смуга лишається як є */
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="px-3.5 py-1.5 bg-canvas border-b border-line flex items-center gap-3 shrink-0 z-10 text-ink" data-testid="group-pin-bar">
      <button
        type="button"
        disabled={!pin.id}
        onClick={() => pin.id && onJump(pin.id)}
        className="flex items-center gap-2 min-w-0 flex-1 min-h-11 text-left disabled:cursor-default"
      >
        <Pin className="w-3.5 h-3.5 text-accent-text shrink-0" strokeWidth={2} />
        <span className="min-w-0 flex-1">
          <span className="block text-caption font-semibold text-accent-text">{tNow('groupsTg.pinned')}</span>
          <span className="block text-caption text-ink-2 truncate">{pin.text ?? tNow('groupProfileSheet.msgMissing')}</span>
        </span>
      </button>
      {chat.powers?.pin && (
        <button
          type="button"
          disabled={busy}
          onClick={() => void unpin()}
          aria-label={tNow('groupProfileSheet.unpin')}
          className="touch-floor p-1 text-ink-3 hover:text-ink hover:bg-raised rounded-control transition-colors disabled:opacity-50"
        >
          <X className="w-3.5 h-3.5" strokeWidth={1.75} />
        </button>
      )}
    </div>
  );
};
