import React from 'react';
import { useProfiles, type Profile } from '../../stores/profilesStore';
import { useUIStore } from '../../stores/uiStore';
import { useLocale } from '../../i18n/useTranslation';
import type { Locale } from '../../i18n/locales';
import { uk as kitUk, en as kitEn } from '../../i18n/messages/parts/profileKit';
import { cs as kitCs } from '../../i18n/messages/parts/cs/profileKit';
import { soundFx } from '../../utils/messengerSound';

type KitKey = keyof typeof kitUk;
const WORDS: Partial<Record<Locale, Record<KitKey, string>>> = { uk: kitUk, en: kitEn, cs: kitCs };

export function kitWord(locale: Locale, key: KitKey, vars: Record<string, string> = {}): string {
  const template = (WORDS[locale] ?? kitEn)[key] ?? kitUk[key];
  return template.replace(/\{(\w+)\}/g, (m, v: string) => vars[v] ?? m);
}

/** Рядок перемикання за правилом тиші профілю, у який щойно перейшли. */
export function quietLine(locale: Locale, p: Profile): string {
  return p.quiet
    ? kitWord(locale, 'kit.quietNow', { name: p.name, from: p.quiet.from, to: p.quiet.to })
    : kitWord(locale, 'kit.quietNone', { name: p.name });
}

export function faceNameOf(p: Profile, userName: string): string {
  return p.passGroups.map((ring) => p.faces[ring]?.name).find(Boolean) || userName;
}

export const ProfileSwitchDiff: React.FC<{ to: Profile; onDone: () => void }> = ({ to, onDone }) => {
  const locale = useLocale();
  const w = (key: KitKey, vars?: Record<string, string>) => kitWord(locale, key, vars);
  const go = () => {
    soundFx.playTap();
    if (!useProfiles.getState().setActive(to.id)) return;
    useUIStore.getState().toast({ kind: 'info', message: quietLine(locale, to) });
    onDone();
  };

  return (
    <div data-testid="profile-switch-diff" className="px-4 pt-3 flex flex-col">
      <button
        type="button"
        data-testid="profile-switch-go"
        onClick={go}
        className="mt-3 h-12 rounded-control bg-accent text-on-accent text-headline font-semibold hover:bg-accent-hover active:bg-accent-press"
      >
        {w('kit.go', { name: to.name })}
      </button>
    </div>
  );
};
