import type { KitAssistant, KitNotify, KitQuick, KitTab, KitWallet, ProfileKit } from '@shared/types/profileKit';
import {
  KIT_ASSISTANT,
  KIT_MAX_QUICK,
  KIT_MAX_TABS,
  KIT_MAX_TABS_BUSINESS,
  KIT_NOTIFY,
  KIT_QUICK,
  KIT_TABS,
  KIT_WALLETS,
} from '@shared/types/profileKit';

const PERSONAL: ProfileKit = {
  tabs: ['map', 'day', 'wallet'],
  quick: ['plan', 'money', 'place'],
  notify: 'circle',
  assistant: 'all',
  wallet: 'personal',
};

const STUDY: ProfileKit = {
  tabs: ['classes', 'schedule'],
  quick: ['assignment', 'attendance', 'grades', 'announce'],
  notify: 'hours',
  assistant: 'spaces',
  wallet: null,
};

const WORK: ProfileKit = {
  tabs: ['tasks', 'shifts'],
  quick: ['task', 'shift', 'approve'],
  notify: 'hours',
  assistant: 'spaces',
  wallet: 'work',
};

const DEV: ProfileKit = {
  tabs: ['repos'],
  quick: ['pr', 'deploy'],
  notify: 'hours',
  assistant: 'spaces',
  wallet: null,
};

const copy = (k: ProfileKit): ProfileKit => ({ ...k, tabs: [...k.tabs], quick: [...k.quick] });

/** Набір за родом профілю. Розробка й Бізнес — у P1 (11-spaces §9); до того — особистий. */
export function kitForKind(kind: string): ProfileKit {
  if (kind === 'study') return copy(STUDY);
  if (kind === 'work') return copy(WORK);
  if (kind === 'dev') return copy(DEV);
  if (kind === 'unnamed') return { ...copy(PERSONAL), assistant: 'none' };
  return copy(PERSONAL);
}

export const kitMaxTabs = (kind: string): number => (kind === 'business' ? KIT_MAX_TABS_BUSINESS : KIT_MAX_TABS);

const isObj = (v: unknown): v is Record<string, unknown> =>
  v !== null && typeof v === 'object' && !Array.isArray(v);

function listOf<T extends string>(raw: unknown, allowed: readonly T[], max: number): T[] | null {
  if (!Array.isArray(raw)) return null;
  const out: T[] = [];
  for (const v of raw) {
    if ((allowed as readonly unknown[]).includes(v) && !out.includes(v as T)) out.push(v as T);
  }
  return out.slice(0, max);
}

const oneOf = <T extends string>(raw: unknown, allowed: readonly T[]): T | undefined =>
  (allowed as readonly unknown[]).includes(raw) ? (raw as T) : undefined;

/** Збережений набір → чистий; чого бракує чи що невідоме — з набору роду. */
export function cleanKit(raw: unknown, kind: string): ProfileKit {
  const base = kitForKind(kind);
  if (!isObj(raw)) return base;
  return {
    tabs: listOf<KitTab>(raw.tabs, KIT_TABS, kitMaxTabs(kind)) ?? base.tabs,
    quick: listOf<KitQuick>(raw.quick, KIT_QUICK, KIT_MAX_QUICK) ?? base.quick,
    notify: oneOf<KitNotify>(raw.notify, KIT_NOTIFY) ?? base.notify,
    assistant: oneOf<KitAssistant>(raw.assistant, KIT_ASSISTANT) ?? base.assistant,
    wallet: raw.wallet === null ? null : (oneOf<KitWallet>(raw.wallet, KIT_WALLETS) ?? base.wallet),
  };
}

export type KitRow =
  | { field: 'tabs'; gone: KitTab[]; added: KitTab[] }
  | { field: 'quick'; gone: KitQuick[]; added: KitQuick[] }
  | { field: 'notify'; from: KitNotify; to: KitNotify }
  | { field: 'assistant'; from: KitAssistant; to: KitAssistant }
  | { field: 'wallet'; from: KitWallet | null; to: KitWallet | null };

/** Що зміниться при переході: лише поля, які справді різняться. */
export function kitDiff(from: ProfileKit, to: ProfileKit): KitRow[] {
  const rows: KitRow[] = [];
  const tabsGone = from.tabs.filter((t) => !to.tabs.includes(t));
  const tabsAdded = to.tabs.filter((t) => !from.tabs.includes(t));
  if (tabsGone.length > 0 || tabsAdded.length > 0) rows.push({ field: 'tabs', gone: tabsGone, added: tabsAdded });
  const quickGone = from.quick.filter((q) => !to.quick.includes(q));
  const quickAdded = to.quick.filter((q) => !from.quick.includes(q));
  if (quickGone.length > 0 || quickAdded.length > 0) rows.push({ field: 'quick', gone: quickGone, added: quickAdded });
  if (from.notify !== to.notify) rows.push({ field: 'notify', from: from.notify, to: to.notify });
  if (from.assistant !== to.assistant) rows.push({ field: 'assistant', from: from.assistant, to: to.assistant });
  if (from.wallet !== to.wallet) rows.push({ field: 'wallet', from: from.wallet, to: to.wallet });
  return rows;
}
