import React, { useMemo, useRef, useState } from 'react';
import { ArrowUp, ChevronRight, X } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import type { Chat } from '../../../types/messenger';
import { soundFx } from '../../../utils/messengerSound';
import { useMessengerStore } from '../../../stores/messengerStore';
import { useUIStore } from '../../../stores/uiStore';
import { displayTitle } from '../chatListFormat';
import { FormSheet, PrimaryButton } from '../FormSheet';
import { nameToken, firstName, recipientCandidates, textFor } from '../../../services/recipients';
import { RecipientAvatar, RecipientChips, RecipientPicker } from './RecipientPicker';

const ICON = { strokeWidth: 1.75 } as const;
const STRIP_MAX = 12;
const HOLD_MS = 450;

/**
 * Верхня смуга людей і груп — вибір адресатів. Дотик вибирає (ще дотики —
 * ще люди), утримання відкриває розмову, «Ще» — повний список з пошуком.
 */
export const RecipientStrip: React.FC<{
  chats: Chat[];
  selected: string[];
  onChange: (ids: string[]) => void;
  onOpenChat: (id: string) => void;
  onMore: () => void;
}> = ({ chats, selected, onChange, onOpenChat, onMore }) => {
  const { t } = useTranslation();
  const top = useMemo(() => recipientCandidates(chats).slice(0, STRIP_MAX), [chats]);
  const hold = useRef<{ id: string; timer: number; fired: boolean } | null>(null);
  if (top.length < 2) return null;
  const chosen = new Set(selected);
  const extra = selected.filter((id) => !top.some((c) => c.id === id)).length;

  const release = () => {
    if (hold.current) window.clearTimeout(hold.current.timer);
  };

  return (
    <div data-recipient-strip data-own-swipe role="group" aria-label={t('pick.strip')} className="flex items-start gap-1 overflow-x-auto no-scrollbar px-2 pt-1 pb-2">
      {top.map((c) => {
        const on = chosen.has(c.id);
        const name = firstName(c) || displayTitle(c.title);
        return (
          <button
            key={c.id}
            type="button"
            data-strip-pick={c.id}
            aria-pressed={on}
            onPointerDown={() => {
              release();
              hold.current = {
                id: c.id,
                fired: false,
                timer: window.setTimeout(() => {
                  if (hold.current) hold.current.fired = true;
                  soundFx.playTap();
                  onOpenChat(c.id);
                }, HOLD_MS),
              };
            }}
            onPointerUp={release}
            onPointerLeave={release}
            onPointerCancel={release}
            onContextMenu={(e) => e.preventDefault()}
            onClick={() => {
              if (hold.current?.fired && hold.current.id === c.id) {
                hold.current = null;
                return;
              }
              soundFx.playTap();
              onChange(on ? selected.filter((id) => id !== c.id) : [...selected, c.id]);
            }}
            className="w-[68px] shrink-0 pt-1 flex flex-col items-center gap-1 rounded-control active:bg-overlay select-none"
          >
            <RecipientAvatar chat={c} on={on} size="w-[52px] h-[52px] text-title-2" />
            <span className={`w-full truncate text-center text-caption ${on ? 'text-ink font-semibold' : 'text-ink-2'}`}>{name}</span>
          </button>
        );
      })}
      <button
        type="button"
        data-strip-more
        onClick={() => {
          soundFx.playTap();
          onMore();
        }}
        className="w-[68px] shrink-0 pt-1 flex flex-col items-center gap-1 rounded-control active:bg-overlay"
      >
        <span className="w-[52px] h-[52px] rounded-full border border-line-strong flex items-center justify-center text-ink-2 text-subhead font-medium">
          {extra > 0 ? `+${extra}` : <ChevronRight className="w-6 h-6" {...ICON} />}
        </span>
        <span className="w-full truncate text-center text-caption text-ink-2">{t('pick.more')}</span>
      </button>
    </div>
  );
};

/** Повний список адресатів — той самий вибір, що й у смузі. */
export const RecipientSheet: React.FC<{
  chats: Chat[];
  selected: string[];
  onChange: (ids: string[]) => void;
  onClose: () => void;
}> = ({ chats, selected, onChange, onClose }) => {
  const { t } = useTranslation();
  return (
    <FormSheet
      open
      tall
      onClose={onClose}
      title={t('pick.to', { n: selected.length })}
      testId="recipient-sheet"
      layerKey="recipients"
      footer={<PrimaryButton onClick={onClose}>{t('pick.next')}</PrimaryButton>}
    >
      <RecipientPicker chats={chats} selected={selected} onChange={onChange} />
    </FormSheet>
  );
};

/**
 * Смуга надсилання під списком: кому (чипи), текст, «Надіслати». Перед
 * надсиланням — перегляд кожному: як саме лист ляже в кожну розмову.
 */
export const SendManyDock: React.FC<{
  chats: Chat[];
  selected: string[];
  onChange: (ids: string[]) => void;
}> = ({ chats, selected, onChange }) => {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [preview, setPreview] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const byId = useMemo(() => new Map(chats.map((c) => [c.id, c])), [chats]);
  const targets = selected.map((id) => byId.get(id)).filter((c): c is Chat => Boolean(c));
  if (!targets.length) return null;
  const ready = text.trim() !== '';

  const sendAll = () => {
    const sendMessage = useMessengerStore.getState().sendMessage;
    for (const c of targets) {
      const body = textFor(text, c);
      if (body) void sendMessage(body, { to: c.id });
    }
    soundFx.playSend();
    useUIStore.getState().toast({ kind: 'success', message: t('pick.sent', { n: targets.length }) });
    setPreview(false);
    setText('');
    onChange([]);
  };

  const insertName = () => {
    const el = input.current;
    const at = el?.selectionStart ?? text.length;
    const token = nameToken();
    const next = `${text.slice(0, at)}${token}${text.slice(at)}`;
    setText(next);
    window.requestAnimationFrame(() => {
      el?.focus();
      el?.setSelectionRange(at + token.length, at + token.length);
    });
  };

  return (
    <>
      <div data-send-many className="shrink-0 border-t-[0.5px] border-line bg-surface pb-[var(--sab)]">
        <div className="flex items-center pr-1">
          <span className="pl-4 pr-1 text-footnote font-medium text-ink-3 shrink-0">{t('pick.to', { n: targets.length })}</span>
          <div className="flex-1 min-w-0">
            <RecipientChips chats={chats} selected={selected} onChange={onChange} />
          </div>
          <button
            type="button"
            data-send-many-clear
            aria-label={t('pick.clear')}
            onClick={() => onChange([])}
            className="w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink-2 active:bg-overlay"
          >
            <X className="w-5 h-5" {...ICON} />
          </button>
        </div>
        <div className="flex items-center gap-2 px-3 pb-2">
          <input
            ref={input}
            data-send-many-text
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && ready) setPreview(true);
            }}
            placeholder={t('pick.message')}
            aria-label={t('pick.message')}
            className="flex-1 min-w-0 h-11 px-4 rounded-full bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
          />
          <button
            type="button"
            data-send-many-name
            onClick={insertName}
            title={t('pick.nameHint')}
            className="h-11 px-3 shrink-0 rounded-full border border-line-strong text-subhead font-medium text-ink-2 active:bg-overlay"
          >
            {t('pick.nameToken')}
          </button>
          <button
            type="button"
            data-send-many-go
            disabled={!ready}
            onClick={() => setPreview(true)}
            aria-label={t('pick.preview', { n: targets.length })}
            className={`w-11 h-11 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center bg-accent text-on-accent ${ready ? 'active:bg-accent-press' : 'opacity-40'}`}
          >
            <ArrowUp className="w-5 h-5" strokeWidth={2} />
          </button>
        </div>
      </div>
      {preview && (
        <FormSheet
          open
          tall
          onClose={() => setPreview(false)}
          title={t('pick.preview', { n: targets.length })}
          testId="send-many-preview"
          layerKey="send-many"
          footer={<PrimaryButton onClick={sendAll}>{t('pick.send', { n: targets.length })}</PrimaryButton>}
        >
          <ul className="pb-2">
            {targets.map((c) => (
              <li key={c.id} data-preview-for={c.id} className="px-4 py-2 flex items-start gap-3">
                <RecipientAvatar chat={c} on={false} size="w-10 h-10 text-subhead" />
                <div className="flex-1 min-w-0">
                  <p className="text-subhead font-semibold text-ink truncate">{displayTitle(c.title)}</p>
                  <p data-preview-text className="mt-1 w-fit max-w-full px-3.5 py-2 rounded-bubble bg-self text-callout text-ink break-words whitespace-pre-wrap">
                    {textFor(text, c)}
                  </p>
                </div>
                <button
                  type="button"
                  data-preview-drop={c.id}
                  aria-label={`${t('pick.drop')}: ${displayTitle(c.title)}`}
                  onClick={() => {
                    const next = selected.filter((id) => id !== c.id);
                    onChange(next);
                    if (!next.length) setPreview(false);
                  }}
                  className="w-12 h-12 min-h-0 min-w-0 shrink-0 rounded-full flex items-center justify-center text-ink-3 active:bg-overlay"
                >
                  <X className="w-5 h-5" {...ICON} />
                </button>
              </li>
            ))}
          </ul>
        </FormSheet>
      )}
    </>
  );
};
