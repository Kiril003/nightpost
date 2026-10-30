/**
 * «Тиша вночі» на екрані «Я». Картка є лише тоді, коли телефон віддав свої
 * години: на ПК моста немає — немає й картки.
 */
import React, { useEffect, useState } from 'react';
import { Check } from 'lucide-react';
import { messengerApi, type QuietHours } from '../../services/messengerApi';
import { isOwnDeviceContact } from '../../services/peopleIdentity';
import { useMessengerStore } from '../../stores/messengerStore';
import { soundFx } from '../../utils/messengerSound';
import { FormSheet, PrimaryButton } from './FormSheet';
import { Avatar } from './Avatar';
import { useTranslation, type Translation } from '../../i18n/useTranslation';
import { translate } from '../../i18n';

const FROM = ['21:00', '22:00', '23:00', '00:00'];
const TO = ['06:00', '07:00', '08:00', '09:00'];

/** «07:00» → «7:00», як у людській мові. */
export const clock = (hhmm: string) => hhmm.replace(/^0(\d)/, '$1');

export const exceptLine = (names: string[], t: Translation['t'] = (key, vars) => translate(key, 'uk', vars)) => {
  if (names.length === 0) return '';
  if (names.length === 1) return t('quietNights.except1', { names: names[0] });
  if (names.length === 2) return t('quietNights.except2', { names: names[0], names2: names[1] });
  return t('quietNights.except3', { names: names[0], names2: names[1], length: names.length - 2 });
};

export const Switch: React.FC<{ on: boolean; onToggle: () => void; label: string }> = ({ on, onToggle, label }) => (
  <button
    type="button"
    role="switch"
    aria-checked={on}
    aria-label={label}
    onClick={onToggle}
    className="shrink-0 w-[52px] h-11 min-h-0 min-w-0 flex items-center"
  >
    <span
      aria-hidden="true"
      className={`relative w-full h-8 rounded-full transition-colors duration-instant ease-standard ${on ? 'bg-accent' : 'bg-overlay'}`}
    >
      <span
        style={{ backgroundColor: 'var(--ph-lit-white)' }}
        className={`absolute top-1 left-1 w-6 h-6 rounded-full transition-transform duration-instant ease-standard ${
          on ? 'translate-x-5' : ''
        }`}
      />
    </span>
  </button>
);

const Choices: React.FC<{ title: string; options: string[]; value: string; onPick: (v: string) => void }> = ({
  title,
  options,
  value,
  onPick,
}) => {
  const all = options.includes(value) ? options : [...options, value].sort();
  return (
    <div className="px-4 pt-3">
      <h3 className="pb-2 text-footnote font-semibold text-ink-3">{title}</h3>
      <div role="radiogroup" aria-label={title} className="flex flex-wrap gap-2">
        {all.map((o) => (
          <button
            key={o}
            type="button"
            role="radio"
            aria-checked={o === value}
            onClick={() => onPick(o)}
            className={`h-11 min-h-0 px-4 rounded-full text-callout font-semibold ${
              o === value ? 'bg-accent text-on-accent' : 'bg-raised text-ink'
            }`}
          >
            {clock(o)}
          </button>
        ))}
      </div>
    </div>
  );
};

export const QuietNightsCard: React.FC = () => {
  const { t } = useTranslation();
  const [quiet, setQuiet] = useState<QuietHours | null>(null);
  const [people, setPeople] = useState<Array<{ id: string; name: string; avatar?: string }>>([]);
  const [sheet, setSheet] = useState(false);

  useEffect(() => {
    let alive = true;
    messengerApi
      .quiet()
      .then((q) => alive && setQuiet(typeof q?.enabled === 'boolean' && Array.isArray(q.except) ? q : null))
      .catch(() => alive && setQuiet(null));
    messengerApi
      .listContacts()
      .then((cs) => {
        if (!alive || !Array.isArray(cs)) return;
        const chats = useMessengerStore.getState().chats;
        const seen = new Set<string>();
        const rows: Array<{ id: string; name: string; avatar?: string }> = [];
        for (const c of cs) {
          if (!c?.peer_node_id || seen.has(c.peer_node_id)) continue;
          if (
            (c as { mine?: boolean }).mine ||
            isOwnDeviceContact(
              { id: c.id ?? '', peer_node_id: c.peer_node_id, display_name: c.display_name || '' },
              chats,
            )
          ) {
            continue;
          }
          seen.add(c.peer_node_id);
          const avatar = c.photo || (c.avatar_state === 'set' ? c.avatar_thumb : c.avatar_thumb) || undefined;
          rows.push({ id: c.peer_node_id, name: c.display_name || '', avatar });
        }
        setPeople(rows);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);

  if (!quiet) return null;

  const patch = async (p: Partial<QuietHours>) => {
    soundFx.playTap();
    try {
      setQuiet(await messengerApi.setQuiet(p));
    } catch {
      /* лишається те, що сказав телефон */
    }
  };

  const nameOf = new Map(people.map((p) => [p.id, p.name || t('quietNights.noName')]));
  const names = quiet.except.map((id) => nameOf.get(id)).filter((n): n is string => !!n);

  return (
    <>
      <section data-testid="me-quiet" className="mx-4 mt-2 rounded-card bg-surface flex items-center gap-3 pr-4">
        <button
          type="button"
          data-testid="me-quiet-open"
          onClick={() => {
            soundFx.playTap();
            setSheet(true);
          }}
          className="flex-1 min-w-0 py-3 pl-4 text-left rounded-card active:bg-raised"
        >
          <span className="block text-headline text-ink">{t('quietNights.title')}</span>
          <span data-testid="me-quiet-line" className="block truncate text-callout text-ink-2">
            {clock(quiet.from)}–{clock(quiet.to)}
            {exceptLine(names, t)}
          </span>
        </button>
        <Switch on={quiet.enabled} label={t('quietNights.title')} onToggle={() => void patch({ enabled: !quiet.enabled })} />
      </section>

      <FormSheet
        open={sheet}
        onClose={() => setSheet(false)}
        title={t('quietNights.title')}
        testId="quiet-sheet"
        footer={<PrimaryButton onClick={() => setSheet(false)}>{t('quietNights.done')}</PrimaryButton>}
      >
        <Choices title={t('quietNights.from')} options={FROM} value={quiet.from} onPick={(from) => void patch({ from })} />
        <Choices title={t('quietNights.until')} options={TO} value={quiet.to} onPick={(to) => void patch({ to })} />
        {people.length > 0 && (
          <>
            <h3 className="px-4 pt-5 pb-1 text-footnote font-semibold text-ink-3">{t('quietNights.who')}</h3>
            <ul>
              {people.map((p) => {
                const picked = quiet.except.includes(p.id);
                return (
                  <li key={p.id}>
                    <button
                      type="button"
                      role="checkbox"
                      aria-checked={picked}
                      onClick={() =>
                        void patch({
                          except: picked ? quiet.except.filter((x) => x !== p.id) : [...quiet.except, p.id],
                        })
                      }
                      className="w-full min-h-14 px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
                    >
                      <Avatar src={p.avatar} name={p.name || t('quietNights.noName')} tone={p.id} radius="rounded-full" className="w-9 h-9 text-subhead" />
                      <span className="flex-1 min-w-0 truncate text-body text-ink">{p.name || t('quietNights.noName')}</span>
                      <span
                        aria-hidden="true"
                        className={`w-6 h-6 shrink-0 rounded-control flex items-center justify-center ${
                          picked ? 'bg-accent text-on-accent' : 'border-2 border-f-ink-3'
                        }`}
                      >
                        {picked && <Check className="w-4 h-4" strokeWidth={3} />}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </FormSheet>
    </>
  );
};
