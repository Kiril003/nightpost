import React, { useState } from 'react';
import { Bookmark, CalendarDays, Copy, Forward, ListChecks, Pin, PinOff, Quote, Shapes, Trash2, UserRound, X } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { Chat } from '../../types/messenger';
import { useMessengerStore } from '../../stores/messengerStore';
import { useUIStore } from '../../stores/uiStore';
import { soundFx } from '../../utils/messengerSound';
import { SAVED_NOTES_ID } from '../../services/messengerApi';
import { isSavedNotes } from './chatListFormat';
import { DoorRow, FormSheet } from './FormSheet';
import {
  allMine,
  inFeedOrder,
  mergeSelection,
  msgKind,
  selectionMarkdown,
  similarIds,
  taskLines,
  type SelectBy,
} from '../../services/msgSelection';

const ICON = { strokeWidth: 1.75 } as const;

const newId = (p: string) => `${p}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

/**
 * Вибір листів (msg-x): лічильник, «вибрати схожі» (автор, рід, день) і дії
 * над усім вибраним. Замість композера, поки вибір триває.
 */
export const SelectionBar: React.FC<{ chat: Chat }> = ({ chat }) => {
  const { t } = useTranslation();
  const ids = useMessengerStore((s) => s.selectedMessageIds);
  const anchorId = useMessengerStore((s) => s.selectionAnchorId);
  const me = useMessengerStore((s) => s.currentUser.id);
  const [confirmDelete, setConfirmDelete] = useState(false);
  if (!ids.length) return null;

  const store = () => useMessengerStore.getState();
  const toast = (message: string) => useUIStore.getState().toast({ kind: 'success', message });
  const picked = inFeedOrder(chat.messages, ids);
  const n = picked.length;
  const anchor = chat.messages.find((m) => m.id === anchorId) ?? picked[picked.length - 1];
  const saved = isSavedNotes(chat);
  const allPinned = n > 0 && picked.every((m) => m.isPinned);
  const done = () => store().clearSelection();

  const similar = (by: SelectBy) => {
    if (!anchor) return;
    soundFx.playTap();
    store().selectMessages(mergeSelection(chat.messages, ids, similarIds(chat.messages, anchor, by)));
  };

  const actions: Array<{ key: string; label: string; icon: React.ReactNode; run: () => void; primary?: boolean; hide?: boolean }> = [
    {
      key: 'forward',
      label: t('sel.forward'),
      icon: <Forward className="w-5 h-5" {...ICON} />,
      primary: true,
      run: () => store().openForwardModal(picked),
    },
    {
      key: 'tasks',
      label: t('sel.tasks'),
      icon: <ListChecks className="w-5 h-5" {...ICON} />,
      run: () => {
        const lines = taskLines(picked);
        if (!lines.length) return;
        const who = store().currentUser.name;
        void store().sendCard('widget:family-checklist', {
          id: newId('fc'),
          title: t('sel.tasksTitle'),
          items: lines.map((text) => ({ id: newId('i'), text, category: '', done: false, addedBy: who })),
        });
        toast(t('sel.tasksDone', { n: lines.length }));
        done();
      },
    },
    {
      key: 'copy',
      label: t('sel.copy'),
      icon: <Copy className="w-5 h-5" {...ICON} />,
      run: () => {
        void navigator.clipboard?.writeText(selectionMarkdown(picked));
        toast(t('sel.copied', { n }));
        done();
      },
    },
    {
      key: 'save',
      label: t('sel.save'),
      icon: <Bookmark className="w-5 h-5" {...ICON} />,
      hide: saved,
      run: () => {
        void (async () => {
          for (const m of picked) await store().forwardMessage(m, SAVED_NOTES_ID);
        })();
        toast(t('sel.saved', { n }));
        done();
      },
    },
    {
      key: 'pin',
      label: allPinned ? t('sel.unpin') : t('sel.pin'),
      icon: allPinned ? <PinOff className="w-5 h-5" {...ICON} /> : <Pin className="w-5 h-5" {...ICON} />,
      run: () => {
        const flip = picked.filter((m) => Boolean(m.isPinned) === allPinned);
        for (const m of flip) store().togglePinMessage(m.id);
        toast(allPinned ? t('sel.unpinned', { n: flip.length }) : t('sel.pinned', { n: flip.length }));
        done();
      },
    },
    {
      key: 'quote',
      label: t('sel.quote'),
      icon: <Quote className="w-5 h-5" {...ICON} />,
      run: () => {
        const first = picked[0];
        if (!first) return;
        const quoted = picked.map((m) => `${m.senderName}: ${(m.text || m.fileData?.name || '').replace(/\s+/g, ' ').trim()}`).join('\n');
        done();
        store().startReply(first, quoted);
      },
    },
    {
      key: 'delete',
      label: t('sel.delete'),
      icon: <Trash2 className="w-5 h-5" {...ICON} />,
      run: () => setConfirmDelete(true),
    },
  ];

  const remove = (forAll: boolean) => {
    setConfirmDelete(false);
    for (const m of picked) void store().deleteMessage(m.id, forAll);
    toast(t('sel.deleted', { n }));
    done();
  };

  const kindWord = anchor ? t(`sel.k.${msgKind(anchor)}` as 'sel.k.text') : '';
  const sender = anchor ? (anchor.isSelf || anchor.senderId === me ? store().currentUser.name : anchor.senderName) : '';
  const by: Array<{ key: SelectBy; label: string; icon: React.ReactNode }> = anchor
    ? [
        { key: 'sender', label: t('sel.bySender', { name: sender.split(/\s+/)[0] || sender }), icon: <UserRound className="w-4 h-4" {...ICON} /> },
        { key: 'kind', label: t('sel.byKind', { kind: kindWord }), icon: <Shapes className="w-4 h-4" {...ICON} /> },
        ...(anchor.sentAt ? [{ key: 'day' as const, label: t('sel.byDay'), icon: <CalendarDays className="w-4 h-4" {...ICON} /> }] : []),
      ]
    : [];

  return (
    <div data-selection-bar className="shrink-0 border-t-[0.5px] border-line bg-surface pb-[var(--sab)] text-ink">
      <div className="h-12 flex items-center gap-1 pl-1 pr-2">
        <button
          type="button"
          data-sel-close
          aria-label={t('sel.close')}
          onClick={() => {
            soundFx.playTap();
            done();
          }}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink active:bg-overlay"
        >
          <X className="w-5 h-5" {...ICON} />
        </button>
        <span data-sel-count className="shrink-0 text-headline font-semibold tnum pr-2">
          {t('sel.count', { n })}
        </span>
        <div role="group" aria-label={t('sel.by')} className="flex-1 min-w-0 flex items-center gap-1.5 overflow-x-auto no-scrollbar">
          {by.map((b) => (
            <button
              key={b.key}
              type="button"
              data-sel-by={b.key}
              onClick={() => similar(b.key)}
              className="touch-floor-inline shrink-0 h-8 px-2.5 rounded-chip border border-line-strong text-footnote font-medium text-ink-2 inline-flex items-center gap-1 active:bg-overlay"
            >
              {b.icon}
              <span className="max-w-[140px] truncate">{b.label}</span>
            </button>
          ))}
        </div>
        <button
          type="button"
          data-sel-action="delete"
          aria-label={t('sel.delete')}
          onClick={() => {
            soundFx.playTap();
            setConfirmDelete(true);
          }}
          className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-danger active:bg-overlay"
        >
          <Trash2 className="w-5 h-5" {...ICON} />
        </button>
      </div>
      <div role="toolbar" aria-label={t('sel.count', { n })} className="flex items-stretch px-2 pb-2">
        {actions
          .filter((a) => !a.hide && a.key !== 'delete')
          .map((a) => (
            <button
              key={a.key}
              type="button"
              data-sel-action={a.key}
              onClick={() => {
                soundFx.playTap();
                a.run();
              }}
              className={`flex-1 min-w-0 h-14 rounded-control flex flex-col items-center justify-center gap-0.5 text-caption font-medium whitespace-nowrap tracking-tight ${
                a.primary ? 'bg-accent text-on-accent active:bg-accent-press' : 'text-ink-2 active:bg-overlay'
              }`}
            >
              {a.icon}
              <span>{a.label}</span>
            </button>
          ))}
      </div>
      {confirmDelete && (
        <FormSheet open onClose={() => setConfirmDelete(false)} title={t('sel.deleteTitle', { n })} testId="sel-delete" layerKey="sel-delete">
          <div className="pb-2">
            <DoorRow tone="danger" icon={<Trash2 className="w-5 h-5" {...ICON} />} label={t('sel.deleteMe')} onClick={() => remove(false)} />
            {!saved && allMine(picked, me) && (
              <DoorRow tone="danger" icon={<Trash2 className="w-5 h-5" {...ICON} />} label={t('sel.deleteAll')} onClick={() => remove(true)} />
            )}
            <DoorRow label={t('sel.cancel')} onClick={() => setConfirmDelete(false)} />
          </div>
        </FormSheet>
      )}
    </div>
  );
};
