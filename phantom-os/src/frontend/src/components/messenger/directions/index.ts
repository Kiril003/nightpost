import type { ProfileKind } from '../../../stores/profilesStore';
import {
  DIRECTIONS,
  hasQuickScreen,
  hasTabScreen,
  type Direction,
  type DirectionId,
  type DirectionQuick,
  type DirectionTab,
  WEB_SLOT_TABS,
} from './registry';
import type { Locale } from '../../../i18n/locales';
import { dirWord } from './words';

export * from './registry';

const KIND_TO_DIRECTION: Record<ProfileKind, DirectionId> = {
  personal: 'personal',
  family: 'personal',
  hobby: 'personal',
  trip: 'personal',
  temp: 'personal',
  own: 'personal',
  work: 'work',
  org: 'work',
  business: 'business',
  place: 'business',
  dev: 'dev',
  study: 'study',
  author: 'author',
  unnamed: 'quiet',
};

export const directionIdOf = (kind: ProfileKind): DirectionId => KIND_TO_DIRECTION[kind] ?? 'personal';

export interface ResolvedDirection extends Direction {
  id: DirectionId;
  /** Вкладки, що справді мають екран. */
  shownTabs: DirectionTab[];
  shownQuick: DirectionQuick[];
}

export function directionOf(kind: ProfileKind): ResolvedDirection {
  const id = directionIdOf(kind);
  const d = DIRECTIONS[id];
  return {
    ...d,
    id,
    shownTabs: d.tabs.filter(hasTabScreen),
    shownQuick: d.quick.filter(hasQuickScreen),
  };
}

/** Рідні вкладки нижньої смуги, які телефон покаже в цьому профілі (Розмови й «Я» є завжди). */
export function nativeBarTabs(kind: ProfileKind): Array<'map' | 'wallet'> {
  return directionOf(kind).shownTabs.filter((t): t is 'map' | 'wallet' => t === 'map' || t === 'wallet');
}

export interface BarSlot {
  id: DirectionTab;
  label: string;
}

/** Власні вкладки напряму після «Розмов»: лише ті, чий веб-екран є, у порядку напряму. */
export function barSlots(kind: ProfileKind, locale: Locale): BarSlot[] {
  return directionOf(kind)
    .shownTabs.filter((t) => WEB_SLOT_TABS.includes(t))
    .map((id) => ({ id, label: dirWord(locale, `dir.tab.${id}`) }));
}
