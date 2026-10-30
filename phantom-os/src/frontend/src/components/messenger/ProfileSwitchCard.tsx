import React from 'react';
import {
  Calendar,
  CalendarClock,
  CheckSquare,
  Code2,
  GraduationCap,
  Image,
  Inbox,
  LayoutGrid,
  Map as MapIcon,
  MessageSquare,
  ShoppingBag,
  Tag,
  User,
  Wallet,
  type LucideIcon,
} from 'lucide-react';
import { directionOf, type DirectionTab } from './directions';
import { ProfileGlyphIcon } from './ProfileLookEditor';
import { glyphForProfile, type Profile } from '../../stores/profilesStore';
import { useLocale } from '../../i18n/useTranslation';
import { kitWord } from './ProfileSwitchDiff';
import { useMessengerStore } from '../../stores/messengerStore';

type BarTab = DirectionTab | 'chats' | 'me';

const ICONS: Record<BarTab, LucideIcon> = {
  chats: MessageSquare,
  me: User,
  map: MapIcon,
  wallet: Wallet,
  tasks: CheckSquare,
  shifts: CalendarClock,
  inbox: Inbox,
  orders: ShoppingBag,
  services: Tag,
  code: Code2,
  classes: GraduationCap,
  schedule: Calendar,
  moments: Image,
  pages: LayoutGrid,
};

/** Вся смуга профілю: Розмови, лише вкладки з екраном, «Я». */
export function barOf(p: Pick<Profile, 'kind'>): BarTab[] {
  return ['chats', ...directionOf(p.kind).shownTabs, 'me'];
}

const Row: React.FC<{ label: string; field: string; children: React.ReactNode }> = ({ label, field, children }) => (
  <div data-testid={`kit-row-${field}`} className="py-1 flex items-baseline gap-3">
    <span className="w-28 shrink-0 text-footnote text-ink-2">{label}</span>
    <span className="flex-1 min-w-0 text-callout text-ink">{children}</span>
  </div>
);

export const ProfileSwitchCard: React.FC<{
  profile: Profile;
  /** Профіль, з якого переходять: його зникомі вкладки закреслюються. */
  from: Profile | null;
  faceName: string;
  expanded: boolean;
  stateLine?: string;
}> = ({ profile, from, faceName, expanded, stateLine }) => {
  const locale = useLocale();
  const photo = useMessengerStore((m) => m.profilePhotos[profile.id]);
  const w = (key: Parameters<typeof kitWord>[1], vars?: Record<string, string>) => kitWord(locale, key, vars);
  const label = (k: BarTab) => w(`dir.tab.${k}`);
  const dir = directionOf(profile.kind);
  const bar = barOf(profile);
  const gone = from && from.id !== profile.id ? barOf(from).filter((x) => !bar.includes(x)) : [];
  const hours = profile.quiet
    ? w('kit.notify.hoursAt', { from: profile.quiet.to, to: profile.quiet.from })
    : w('kit.notify.always');

  return (
    <>
      <span className="flex items-center gap-3 w-full">
        <span
          aria-hidden="true"
          className="w-10 h-10 shrink-0 rounded-full bg-accent-soft text-accent-text flex items-center justify-center overflow-hidden"
        >
          {photo ? (
            <img data-testid={`profile-photo-${profile.id}`} src={photo} alt="" className="w-full h-full object-cover" />
          ) : (
            <ProfileGlyphIcon glyph={glyphForProfile(profile)} className="w-5 h-5" />
          )}
        </span>
        <span className="flex-1 min-w-0 flex flex-col">
          <span className="truncate text-headline font-semibold text-ink">{profile.name}</span>
          <span data-testid={`profile-summary-${profile.id}`} className="truncate text-footnote text-ink-2">
            {bar.map(label).join(' · ')}
          </span>
        </span>
        {stateLine && <span className="shrink-0 pr-9 text-footnote font-semibold text-accent-text tnum">{stateLine}</span>}
      </span>
      <span data-testid={`profile-chips-${profile.id}`} className="flex flex-wrap gap-2 pt-3">
        {bar.map((k) => {
          const Icon = ICONS[k];
          return (
            <span
              key={k}
              data-testid={`chip-${k}`}
              aria-label={label(k)}
              className="w-9 h-9 rounded-control bg-raised text-ink-2 flex items-center justify-center"
            >
              <Icon className="w-[18px] h-[18px]" strokeWidth={1.75} />
            </span>
          );
        })}
      </span>
      {expanded && (
        <span data-testid={`profile-rows-${profile.id}`} className="flex flex-col pt-3 w-full">
          <Row field="tabs" label={w('kit.tabs')}>
            {gone.map((k) => (
              <s key={k} className="text-ink-3 mr-1">{label(k)}</s>
            ))}
            {bar.map(label).join(', ')}
          </Row>
          {dir.shownQuick.length > 0 && (
            <Row field="quick" label={w('kit.quick')}>
              {dir.shownQuick.map((q) => w(`dir.quick.${q}`)).join(', ')}
            </Row>
          )}
          <Row field="notify" label={w('kit.notify')}>{hours}</Row>
          <Row field="wallet" label={w('kit.wallet')}>
            {w(dir.wallet ? 'dir.wallet.on' : 'dir.wallet.off')}
          </Row>
          <Row field="face" label={w('kit.face')}>{faceName}</Row>
        </span>
      )}
    </>
  );
};
