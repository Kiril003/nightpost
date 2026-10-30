import React, { useState } from 'react';
import { Briefcase, ChevronRight, GraduationCap, Home, Mountain, Plus, UserPlus, Users, type LucideIcon } from 'lucide-react';
import { Avatar } from '../Avatar';
import { useTranslation } from '../../../i18n/useTranslation';
import { useProfiles } from '../../../stores/profilesStore';
import { useChatProfiles } from '../../../stores/chatProfileStore';
import { useSpaceNav } from '../../../services/spaces/spaceNav';
import { createSpace, type SpacePerson } from '../../../services/spaces/createSpace';
import { SEED_ROOMS, SEED_TEMPLATES, type SeedTemplate } from '../../../services/spaces/spaceTemplates';
import { SectionLabel, SpaceFrame, SpacePrimary } from './SpaceFrame';
import { PeoplePickSheet } from './PeoplePickSheet';

const TPL_ICON: Record<SeedTemplate, LucideIcon> = { club: Mountain, course: GraduationCap, team: Briefcase, home: Home, blank: Plus };

/** Новий простір (sv-create): назва, шаблон зі складом кімнат, люди, одна дія. */
export const SpaceCreate: React.FC = () => {
  const { t } = useTranslation();
  const profileId = useProfiles((s) => s.activeId);
  const nav = useSpaceNav();
  const [name, setName] = useState('');
  const [tpl, setTpl] = useState<SeedTemplate>('club');
  const [people, setPeople] = useState<SpacePerson[]>([]);
  const [picking, setPicking] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const ready = name.trim().length > 0 && people.length > 0 && !busy;
  const TplIcon = tpl === 'blank' ? Users : TPL_ICON[tpl];

  const submit = async () => {
    if (!ready) return;
    setBusy(true);
    setNote(null);
    try {
      const chatId = await createSpace(profileId, name.trim(), tpl, people, t);
      useChatProfiles.getState().assign(chatId, profileId);
      nav.swap({ s: 'home', chatId });
    } catch (e) {
      setNote(e instanceof Error && e.message ? e.message : t('space.failed'));
      setBusy(false);
    }
  };

  return (
    <SpaceFrame
      testId="space-create"
      title={t('space.new')}
      onBack={nav.pop}
      closeIcon
      footer={
        <SpacePrimary testId="space-create-go" className="w-full" disabled={!ready} onClick={submit}>
          {busy ? t('space.creating') : t('space.createBtn')}
        </SpacePrimary>
      }
    >
      <div className="px-4 pt-2 flex items-center gap-3">
        {name.trim() ? (
          <Avatar name={name.trim()} tone={name.trim()} radius="rounded-control" className="w-14 h-14 text-[22px]" />
        ) : (
          <span data-testid="space-name-tile" aria-hidden className="w-14 h-14 shrink-0 rounded-control bg-raised text-ink-2 flex items-center justify-center">
            <TplIcon className="w-6 h-6" strokeWidth={1.75} />
          </span>
        )}
        <input
          data-testid="space-name"
          value={name}
          maxLength={64}
          onChange={(e) => setName(e.target.value)}
          placeholder={t('space.namePh')}
          aria-label={t('space.name')}
          className="flex-1 min-w-0 h-12 px-4 rounded-full bg-raised text-body text-ink placeholder:text-ink-3 focus:outline focus:outline-2 focus:outline-accent-line"
        />
      </div>
      <SectionLabel>{t('space.start')}</SectionLabel>
      <ul role="radiogroup" aria-label={t('space.start')}>
        {SEED_TEMPLATES.map((k) => {
          const on = k === tpl;
          const Icon = TPL_ICON[k];
          return (
            <li key={k}>
              <button
                type="button"
                role="radio"
                aria-checked={on}
                data-testid={`space-tpl-${k}`}
                onClick={() => setTpl(k)}
                className="w-full min-h-[64px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
              >
                <span className="w-10 h-10 shrink-0 rounded-full bg-overlay text-ink-2 flex items-center justify-center">
                  <Icon className="w-5 h-5" strokeWidth={1.75} aria-hidden />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-headline text-ink">{t(`space.tpl.${k}`)}</span>
                  <span className="block truncate text-subhead text-ink-2">{SEED_ROOMS[k].map((r) => t(r[2])).join(' · ')}</span>
                </span>
                <span className={`w-6 h-6 shrink-0 rounded-full border-2 flex items-center justify-center ${on ? 'border-ink' : 'border-line'}`}>
                  {on && <span className="w-3 h-3 rounded-full bg-ink" />}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
      <SectionLabel>{t('space.people')}</SectionLabel>
      <button
        type="button"
        data-testid="space-people-row"
        onClick={() => setPicking(true)}
        className="w-full min-h-[56px] px-4 py-2 flex items-center gap-3 text-left active:bg-raised"
      >
        <span className="w-10 h-10 shrink-0 rounded-full bg-overlay text-ink-2 flex items-center justify-center">
          {people.length ? <Users className="w-5 h-5" strokeWidth={1.75} aria-hidden /> : <UserPlus className="w-5 h-5" strokeWidth={1.75} aria-hidden />}
        </span>
        <span className={`flex-1 text-headline ${people.length ? 'text-ink' : 'text-accent-text'}`}>
          {people.length ? t('space.people.n', { n: people.length }) : t('space.people.add')}
        </span>
        {people.length > 0 && <ChevronRight className="w-5 h-5 text-ink-3" strokeWidth={2} aria-hidden />}
      </button>
      {note && <p data-testid="space-create-note" className="px-4 pt-2 text-footnote text-danger">{note}</p>}
      <PeoplePickSheet
        open={picking}
        picked={people}
        onClose={() => setPicking(false)}
        onDone={(p) => {
          setPeople(p);
          setPicking(false);
        }}
      />
    </SpaceFrame>
  );
};
