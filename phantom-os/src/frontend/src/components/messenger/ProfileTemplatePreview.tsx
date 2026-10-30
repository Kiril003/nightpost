import React, { useState } from 'react';
import { ChevronLeft } from 'lucide-react';
import { ProfileGlyphIcon } from './ProfileLookEditor';
import { passedGroups } from '../../services/profilePass';
import {
  glyphForProfile,
  useProfiles,
  kindLabel,
  templateDefaults,
  type ProfileKind,
} from '../../stores/profilesStore';
import { readLook, type Accent } from '../../services/appearance';
import { useTranslation } from '../../i18n/useTranslation';
import type { MessageKey } from '../../i18n/messages/uk';
import { soundFx } from '../../utils/messengerSound';

const Row: React.FC<{ label: string; field: string; children: React.ReactNode }> = ({ label, field, children }) => (
  <div data-testid={`tpl-row-${field}`} className="min-h-11 py-2 flex items-baseline gap-3">
    <span className="w-28 shrink-0 text-caption text-ink-2">{label}</span>
    <span className="flex-1 min-w-0 text-body text-ink">{children}</span>
  </div>
);

export const ProfileTemplatePreview: React.FC<{
  kind: ProfileKind;
  onBack: () => void;
  onCreate: (name: string) => void;
}> = ({ kind, onBack, onCreate }) => {
  const { t } = useTranslation();
  const d = templateDefaults(kind);
  const duplicatePersonal = useProfiles((s) => kind === 'personal' && s.profiles.some((p) => p.kind === 'personal'));
  const [name, setName] = useState(kindLabel(kind));
  const accent: Accent = d.look?.accent ?? readLook().accent;
  const circles = passedGroups(d.passGroups);
  const circlesText =
    circles.length === 0
      ? t('profilesR.preview.circlesNone')
      : d.passGroups.length === 0 || circles.length === 5
        ? t('profilesR.preview.circlesAll')
        : circles.map((g) => t(`profiles.group.${g}` as MessageKey)).join(', ');
  const quietText = d.quiet
    ? t('profilesR.preview.quiet', { from: d.quiet.from, to: d.quiet.to })
    : t('profilesR.preview.quietNone');

  return (
    <div data-testid="profile-template-preview" data-kind={kind} className="px-4 pb-5 flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="profile-template-preview-back"
          aria-label={t('formSheet.back')}
          onClick={() => {
            soundFx.playTap();
            onBack();
          }}
          className="w-11 h-11 min-h-0 min-w-0 rounded-full bg-raised flex items-center justify-center text-ink"
        >
          <ChevronLeft className="w-5 h-5" strokeWidth={1.75} aria-hidden="true" />
        </button>
        <span className="text-title-2 text-ink">{kindLabel(kind)}</span>
      </div>

      <label className="flex flex-col gap-1">
        <span className="text-caption text-ink-2">{t('profilesR.preview.name')}</span>
        <input
          type="text"
          data-testid="profile-template-name"
          maxLength={40}
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="h-11 px-3 rounded-control bg-raised border border-line text-body text-ink"
        />
      </label>

      <div className="rounded-card bg-raised px-4 flex flex-col divide-y divide-line">
        <Row label={t('profilesR.preview.look')} field="look">
          <span data-accent={accent} className="inline-flex items-center gap-2 text-accent-text">
            <ProfileGlyphIcon glyph={glyphForProfile({ kind, look: d.look })} className="w-4 h-4" />
            <span className="w-3 h-3 rounded-full bg-accent" />
          </span>
        </Row>
        <Row label={t('profilesR.preview.circles')} field="circles">{circlesText}</Row>
        <Row label={t('profilesR.preview.notify')} field="notify">{quietText}</Row>
        {d.expiresAt ? <Row label={t('profilesR.preview.seen')} field="seen">{t('profilesR.preview.expires')}</Row> : null}
      </div>

      <div data-pinned-action className="sticky bottom-0 bg-canvas pt-3">
        <button
          type="button"
          data-testid="profile-template-create"
          disabled={duplicatePersonal}
          onClick={() => {
            soundFx.playTap();
            onCreate(name.trim() || kindLabel(kind));
          }}
          className="w-full min-h-12 py-2 rounded-control bg-accent text-on-accent text-headline disabled:opacity-40"
        >
          {t('profilesR.preview.create')}
        </button>
      </div>
    </div>
  );
};
