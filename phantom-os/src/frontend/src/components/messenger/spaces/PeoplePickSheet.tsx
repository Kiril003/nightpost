import React, { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { useTranslation } from '../../../i18n/useTranslation';
import { messengerApi, type NodeContact } from '../../../services/messengerApi';
import type { SpacePerson } from '../../../services/spaces/createSpace';
import { FormSheet } from '../FormSheet';
import { Avatar } from '../kit/Avatar';
import { displayTitle } from '../chatListFormat';

/** Вибір людей з контактів. Без ключа людину в гілку не покличеш — такі рядки вимкнені. */
export const PeoplePickSheet: React.FC<{
  open: boolean;
  picked: readonly SpacePerson[];
  exclude?: readonly string[];
  onClose: () => void;
  onDone: (people: SpacePerson[]) => void;
}> = ({ open, picked, exclude = [], onClose, onDone }) => {
  const { t } = useTranslation();
  const [rows, setRows] = useState<NodeContact[] | null>(null);
  const [refused, setRefused] = useState<string | null>(null);
  const [sel, setSel] = useState<SpacePerson[]>([...picked]);

  useEffect(() => {
    if (!open) return;
    setSel([...picked]);
    let alive = true;
    messengerApi
      .listContacts()
      .then((r) => alive && setRows(r.filter((c) => !exclude.includes(c.peer_node_id || c.id))))
      .catch((e) => alive && setRefused(e instanceof Error ? e.message : t('space.inviteFailed')));
    return () => {
      alive = false;
    };
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (c: NodeContact) =>
    setSel((s) => (s.some((p) => p.id === c.id) ? s.filter((p) => p.id !== c.id) : [...s, { id: c.id, nodeId: c.peer_node_id || c.id }]));

  return (
    <FormSheet
      open={open}
      onClose={onClose}
      title={t('space.people.pick')}
      tall
      testId="space-people"
      footer={
        <button
          type="button"
          data-testid="space-people-done"
          disabled={sel.length === 0}
          onClick={() => onDone(sel)}
          className="w-full h-12 min-h-0 rounded-full bg-accent text-on-accent text-headline font-semibold disabled:opacity-50"
        >
          {sel.length ? `${t('space.people.done')} · ${sel.length}` : t('space.people.done')}
        </button>
      }
    >
      {refused ? (
        <p className="px-4 py-3 text-callout text-danger">{refused}</p>
      ) : rows === null ? (
        <p className="px-4 py-3 text-callout text-ink-3">{t('space.loading')}</p>
      ) : rows.length === 0 ? (
        <p data-testid="space-people-empty" className="px-4 py-3 text-callout text-ink-3">{t('space.people.empty')}</p>
      ) : (
        <ul>
          {rows.map((c) => {
            const on = sel.some((p) => p.id === c.id);
            return (
              <li key={c.id}>
                <button
                  type="button"
                  role="checkbox"
                  aria-checked={on}
                  disabled={!c.session_ready}
                  onClick={() => toggle(c)}
                  className="w-full min-h-[64px] px-4 py-2 flex items-center gap-3.5 text-left active:bg-raised"
                >
                  <Avatar name={displayTitle(c.display_name)} src={c.avatar_thumb} tone={c.peer_node_id} radius="rounded-full" className={`w-10 h-10 ${c.session_ready ? '' : 'opacity-50'}`} />
                  <span className="flex-1 min-w-0">
                    <span className={`block truncate text-headline ${c.session_ready ? 'text-ink' : 'text-ink-3'}`}>{displayTitle(c.display_name)}</span>
                    {!c.session_ready && <span className="block text-caption text-ink-3">{t('space.people.away')}</span>}
                  </span>
                  <span className={`w-6 h-6 shrink-0 rounded-full border-2 flex items-center justify-center ${on ? 'border-ink bg-ink text-canvas' : 'border-line'}`}>
                    {on && <Check className="w-4 h-4" strokeWidth={2.5} aria-hidden />}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </FormSheet>
  );
};
