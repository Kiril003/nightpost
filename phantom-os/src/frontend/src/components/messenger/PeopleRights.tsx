import React, { useState } from 'react';
import { Check, ChevronRight, Eye, X } from 'lucide-react';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import type { CircleInfo } from '../../services/circlesApi';
import { circleName } from '../../stores/circlesStore';
import {
  ENFORCED_CAPS,
  rightOptions,
  rightsOf,
  useCircleRights,
  type RightCap,
  type RightValue,
} from '../../stores/circleRightsStore';
import { useMessengerStore } from '../../stores/messengerStore';
import { useProfiles } from '../../stores/profilesStore';
import { soundFx } from '../../utils/messengerSound';
import { DoorGroup, DoorRow, FormSheet } from './FormSheet';
import { EmptyState } from './kit/EmptyState';

const capKey = (cap: RightCap) => `people.cap.${cap}` as MessageKey;
const valKey = (v: RightValue) => `people.val.${v}` as MessageKey;

export const CircleRightsView: React.FC<{ circle: CircleInfo; memberCount: number }> = ({ circle, memberCount }) => {
  const { t } = useTranslation();
  const overrides = useCircleRights((s) => s.overrides);
  const rights = rightsOf(circle, overrides);
  const [editing, setEditing] = useState<RightCap | null>(null);
  const [choice, setChoice] = useState<RightValue>('no');

  const open = (cap: RightCap) => {
    soundFx.playTap();
    setChoice(rights[cap]);
    setEditing(cap);
  };
  const changed = editing !== null && choice !== rights[editing];

  return (
    <div data-testid="rights-screen" className="pt-2">
      <DoorGroup>
        {ENFORCED_CAPS.map((cap) => (
          <DoorRow
            key={cap}
            label={t(capKey(cap))}
            onClick={() => open(cap)}
            trailing={
              <span className="flex items-center gap-1.5 text-subhead text-ink-2">
                <span data-testid={`right-${cap}`}>{t(valKey(rights[cap]))}</span>
                <ChevronRight className="w-4 h-4 text-ink-3" strokeWidth={1.75} aria-hidden />
              </span>
            }
          />
        ))}
      </DoorGroup>

      <FormSheet
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing ? t(capKey(editing)) : ''}
        testId="rights-sheet"
        footer={
          <div className="flex flex-col gap-2">
            <button
              type="button"
              data-testid="rights-apply"
              disabled={!changed}
              onClick={() => {
                if (editing && changed) useCircleRights.getState().set(circle.id, editing, choice);
                setEditing(null);
              }}
              className="w-full h-12 rounded-control bg-accent text-headline text-on-accent disabled:opacity-40"
            >
              {t('people.rightsApply', { count: memberCount })}
            </button>
            <button
              type="button"
              onClick={() => setEditing(null)}
              className="w-full h-12 rounded-control text-headline text-ink-2 active:bg-raised"
            >
              {t('people.cancel')}
            </button>
          </div>
        }
      >
        {editing && (
          <div className="pb-2" role="radiogroup">
            <p className="px-4 pb-2 text-footnote text-ink-2">
              {t('people.rightsNow', { circle: circleName(circle, t), value: t(valKey(rights[editing])) })}
            </p>
            <DoorGroup inset="text">
              {rightOptions(editing).map((v) => (
                <DoorRow
                  key={v}
                  label={t(valKey(v))}
                  onClick={() => setChoice(v)}
                  trailing={
                    <span
                      role="radio"
                      aria-checked={choice === v}
                      data-testid={`right-opt-${v}`}
                      className="w-6 h-6 rounded-full border border-line flex items-center justify-center"
                    >
                      {choice === v && <span className="w-3 h-3 rounded-full bg-ink" />}
                    </span>
                  }
                />
              ))}
            </DoorGroup>
            {changed && (
              <div data-testid="rights-changes" className="mx-4 mt-3 px-4 py-3 rounded-card bg-raised text-subhead text-ink">
                {t('people.rightsChanges', { count: memberCount })}
              </div>
            )}
          </div>
        )}
      </FormSheet>
    </div>
  );
};

export const SeeAsView: React.FC<{ circles: CircleInfo[]; initialId: string }> = ({ circles, initialId }) => {
  const { t } = useTranslation();
  const overrides = useCircleRights((s) => s.overrides);
  const me = useMessengerStore((s) => s.currentUser);
  const profile = useProfiles((s) => s.profiles.find((p) => p.id === s.activeId));
  const [circleId, setCircleId] = useState(initialId);
  const circle = circles.find((c) => c.id === circleId) ?? circles[0];
  if (!circle) return null;

  const over = profile?.faces[circle.id] ?? {};
  const name = over.name ?? me.name ?? '';
  const status = over.status ?? me.status ?? '';
  const bio = over.bio ?? me.bio ?? '';
  const rights = rightsOf(circle, overrides);
  const shownText = [status, bio].filter(Boolean).join(' · ');
  const visible = ENFORCED_CAPS.filter((cap) => rights[cap] !== 'no');
  const hidden = ENFORCED_CAPS.filter((cap) => rights[cap] === 'no');

  return (
    <div data-testid="seeas-screen" className="pt-2">
      <div className="px-4 pb-3 flex flex-wrap gap-2" role="tablist">
        {circles.map((c) => (
          <button
            key={c.id}
            type="button"
            role="tab"
            aria-selected={c.id === circle.id}
            data-testid={`seeas-chip-${c.id}`}
            onClick={() => {
              soundFx.playTap();
              setCircleId(c.id);
            }}
            className={`h-10 min-h-[44px] px-4 rounded-full border text-subhead ${
              c.id === circle.id ? 'bg-raised border-line text-ink' : 'border-line text-ink-2'
            }`}
          >
            {circleName(c, t)}
          </button>
        ))}
      </div>

      <div data-testid="seeas-card" className="mx-4 px-4 py-4 rounded-card bg-surface border border-line">
        {name || shownText ? (
          <>
            <div className="text-headline text-ink">{name}</div>
            {shownText && <div className="text-subhead text-ink-2">{shownText}</div>}
          </>
        ) : (
          <EmptyState testId="seeas-empty" icon={<Eye className="w-6 h-6" strokeWidth={1.75} />} label={t('people.seeAsEmpty')} />
        )}
      </div>

      <h3 className="px-4 pt-5 pb-2 text-footnote font-semibold text-ink-2">
        {t('people.seeAsVisible', { count: visible.length })}
      </h3>
      <DoorGroup>
        {visible.map((cap) => (
          <div key={cap} data-testid={`seeas-visible-${cap}`} className="min-h-12 px-4 flex items-center justify-between">
            <span className="text-body text-ink">{t(capKey(cap))}</span>
            <span className="text-subhead text-ink-2">{t(valKey(rights[cap]))}</span>
          </div>
        ))}
      </DoorGroup>

      {hidden.length > 0 && (
        <>
          <h3 className="px-4 pt-5 pb-2 text-footnote font-semibold text-ink-2">
            {t('people.seeAsHidden', { count: hidden.length })}
          </h3>
          <DoorGroup>
            {hidden.map((cap) => (
              <div key={cap} data-testid={`seeas-hidden-${cap}`} className="min-h-12 px-4 flex items-center">
                <span className="text-body text-ink-3">{t(capKey(cap))}</span>
              </div>
            ))}
          </DoorGroup>
        </>
      )}
    </div>
  );
};

export const MoveConfirmBody: React.FC<{ name: string; lost: RightCap[] }> = ({ name, lost }) => {
  const { t } = useTranslation();
  return (
    <div data-testid="move-confirm" className="pb-2">
      <h3 className="px-4 pb-2 text-footnote font-semibold text-ink-2">{t('people.moveStops', { name })}</h3>
      <DoorGroup>
        {lost.map((cap) => (
          <div key={cap} data-testid={`move-lost-${cap}`} className="min-h-12 px-4 flex items-center gap-3">
            <X className="w-5 h-5 text-ink-2" strokeWidth={1.75} aria-hidden />
            <span className="text-body text-ink">{t(capKey(cap))}</span>
          </div>
        ))}
      </DoorGroup>
      <div className="min-h-12 px-4 pt-3 flex items-center justify-between gap-4">
        <span className="text-subhead text-ink">{t('people.moveSentBefore')}</span>
        <span className="flex items-center gap-1 text-subhead text-ink-2">
          <Check className="w-4 h-4" strokeWidth={1.75} aria-hidden />
          {t('people.moveStays')}
        </span>
      </div>
    </div>
  );
};
