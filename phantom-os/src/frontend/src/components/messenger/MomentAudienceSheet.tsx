/** «Хто побачить»: листок вибору аудиторії моменту. Рядки — ті самі сходинки, що й у моста. */
import React from 'react';
import { createPortal } from 'react-dom';
import { Eye, Lock, User, Users, type LucideIcon } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { pluralKey } from '../chat/tNow';
import { Avatar } from './Avatar';
import { circleLabel, type MomentPerson } from './MomentViewer';
import type { Reach } from '../../services/moments';
import type { AudienceCount } from '../../services/audienceCount';
import { chipTone } from './kit/FilterChip';

export interface AudienceRung {
  reach: Reach;
  key: MessageKey;
}

/** Сходинки «Хто побачить» — однакові в новому моменті й у правці аудиторії. */
export const AUDIENCE_RUNGS: { reach: Reach; key: 'moments.rungMe' | 'moments.rungPeople' | 'moments.rungCircles' | 'moments.rungContacts' | 'moments.rungEveryone' }[] = [
  { reach: 'me', key: 'moments.rungMe' },
  { reach: 'people', key: 'moments.rungPeople' },
  { reach: 'circles', key: 'moments.rungCircles' },
  { reach: 'contacts', key: 'moments.rungContacts' },
  { reach: 'everyone', key: 'moments.rungEveryone' },
];

export interface MomentAudienceSheetProps {
  rungs: AudienceRung[];
  reach: Reach;
  onReach: (r: Reach) => void;
  circles: { name: string; ids: string[] }[];
  chosenCircles: string[];
  onToggleCircle: (name: string) => void;
  people: MomentPerson[];
  activePeopleIds: string[];
  onTogglePerson: (id: string, on: boolean) => void;
  count: AudienceCount | null;
  /** Кілька профілів: рахунок — «Бачать N», люди лише цього профілю. */
  seeN?: boolean;
  ready: boolean;
  noOne: boolean;
  outerRings: readonly ('social' | 'work')[];
  onLeaveOut: (ring: 'social' | 'work') => void;
  onDone: () => void;
  /** Тап поза листком; без нього — те саме, що «Готово». */
  onCancel?: () => void;
  doneLabel?: string;
  doneDisabled?: boolean;
}

const ICON: Record<Reach, LucideIcon> = {
  me: Lock,
  people: User,
  circles: Users,
  contacts: Users,
  everyone: Eye,
};

const Chip: React.FC<{ on: boolean; onClick: () => void; children: React.ReactNode; testId?: string }> = ({ on, onClick, children, testId }) => (
  <button
    type="button"
    data-testid={testId}
    aria-pressed={on}
    onClick={onClick}
    className={`shrink-0 h-11 min-h-0 px-4 rounded-full border text-subhead ${chipTone(on)}`}
  >
    {children}
  </button>
);

export const MomentAudienceSheet: React.FC<MomentAudienceSheetProps> = (p) => {
  const { t, locale } = useTranslation();
  const sub = (r: Reach): string =>
    r === 'me' ? t('momentsR.deviceOnly') : r === 'contacts' ? t('momentsR.rungContactsSub') : '';
  return createPortal(
    <div
      data-moment-audience
      className="fixed inset-0 z-[1310] flex flex-col justify-end"
      style={{ backgroundColor: 'var(--np-moment-label)' }}
      onClick={p.onCancel ?? p.onDone}
      onPointerDown={(e) => e.stopPropagation()}
      onPointerUp={(e) => e.stopPropagation()}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-label={t('moments.whoSees')}
        className="rounded-t-[16px] pt-3 max-h-[88%] flex flex-col"
        style={{ backgroundColor: 'var(--ph-canvas)' }}
        onClick={(e) => e.stopPropagation()}
      >
        <span aria-hidden className="mx-auto mb-3 block w-9 h-1 rounded-full bg-line-strong shrink-0" />
        <h3 className="px-4 text-title-2 text-ink shrink-0">{t('moments.whoSees')}</h3>
        <div data-moment-audience-scroll className="flex-1 min-h-0 overflow-y-auto no-scrollbar px-4">
        <div role="radiogroup" aria-label={t('moments.whoSees')} className="mt-2">
          {p.rungs.map((r) => {
            const on = p.reach === r.reach;
            const Icon = ICON[r.reach];
            return (
              <button
                key={r.reach}
                type="button"
                role="radio"
                aria-checked={on}
                data-testid={`aud-${r.reach}`}
                onClick={() => p.onReach(r.reach)}
                className="w-full min-h-[60px] min-w-0 flex items-center gap-3 text-left"
              >
                <span className="w-10 h-10 shrink-0 rounded-full bg-raised flex items-center justify-center text-ink-2">
                  <Icon className="w-5 h-5" strokeWidth={1.75} />
                </span>
                <span className="flex-1 min-w-0">
                  <span className="block text-body text-ink truncate">{t(r.key)}</span>
                  {sub(r.reach) && <span className="block text-footnote text-ink-3 truncate">{sub(r.reach)}</span>}
                </span>
                <span aria-hidden className={`w-5 h-5 shrink-0 rounded-full border-2 flex items-center justify-center ${on ? 'border-ink' : 'border-line-strong'}`}>
                  {on && <span className="w-2.5 h-2.5 rounded-full bg-ink" />}
                </span>
              </button>
            );
          })}
        </div>
        {!p.noOne && p.reach === 'circles' && (
          <div className="mt-2 flex gap-2 flex-wrap">
            {p.circles.map((c) => (
              <Chip key={c.name} testId={`aud-circle-${c.name}`} on={p.chosenCircles.includes(c.name)} onClick={() => p.onToggleCircle(c.name)}>
                {`${circleLabel(t, c.name)} · ${c.ids.length}`}
              </Chip>
            ))}
          </div>
        )}
        {!p.noOne && p.reach === 'people' && (
          <ul className="mt-2">
            {p.people.map((x) => {
              const on = p.activePeopleIds.includes(x.id);
              return (
                <li key={x.id}>
                  <label className="h-14 flex items-center gap-3 cursor-pointer">
                    <Avatar src={x.avatar} name={x.name} tone={x.id} className="w-10 h-10 text-subhead" />
                    <span className="flex-1 min-w-0 truncate text-body text-ink">{x.name}</span>
                    <input
                      type="checkbox"
                      data-moment-person={x.id}
                      checked={on}
                      onChange={() => p.onTogglePerson(x.id, on)}
                      className="w-5 h-5 accent-[var(--ph-accent)]"
                    />
                  </label>
                </li>
              );
            })}
          </ul>
        )}
        {!p.noOne && p.reach === 'people' && p.activePeopleIds.length === 0 && <p className="mt-2 text-footnote text-ink-3">{t('moments.pickSomeone')}</p>}
        {!p.noOne && p.reach === 'circles' && p.chosenCircles.length === 0 && <p className="mt-2 text-footnote text-ink-3">{t('moments.pickCircle')}</p>}
        {p.ready && p.count && p.outerRings.length > 0 && (
          <div className="mt-3 flex gap-2 flex-wrap">
            {p.outerRings.map((ring) => (
              <button
                key={ring}
                type="button"
                data-testid={`aud-leave-${ring}`}
                onClick={() => p.onLeaveOut(ring)}
                className="shrink-0 h-11 min-h-[44px] px-3 rounded-full bg-raised text-footnote text-ink-2"
              >
                {t('moments.leaveOut', { ring: circleLabel(t, ring) })}
              </button>
            ))}
          </div>
        )}
        {p.ready && p.count && (
          <p data-testid="aud-count" className="mt-3 text-footnote text-ink-3">
            {[
              p.seeN ? t('moments.seeN', { n: p.count.total }) : `${p.count.total} ${t(pluralKey(locale, p.count.total, 'moments.people'))}`,
              ...p.outerRings.map((r) => `${p.count!.byRing[r]!.length} ${circleLabel(t, r)}`),
            ].join(' · ')}
          </p>
        )}
        </div>
        <div className="shrink-0 px-4 pt-3" style={{ paddingBottom: 'max(16px, env(safe-area-inset-bottom))' }}>
          <button
            type="button"
            data-moment-audience-done
            disabled={p.doneDisabled}
            onClick={p.onDone}
            className="w-full min-h-12 py-2 rounded-full bg-accent text-on-accent text-headline disabled:opacity-40"
          >
            {p.doneLabel ?? t('momentsR.audienceDone')}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
};
