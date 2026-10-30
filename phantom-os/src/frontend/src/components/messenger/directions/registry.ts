export type DirectionId = 'personal' | 'work' | 'business' | 'dev' | 'study' | 'author' | 'quiet';

export type DirectionTab =
  | 'map'
  | 'wallet'
  | 'tasks'
  | 'shifts'
  | 'inbox'
  | 'orders'
  | 'services'
  | 'code'
  | 'classes'
  | 'schedule'
  | 'moments'
  | 'pages';

export type DirectionQuick =
  | 'write'
  | 'moment'
  | 'meet'
  | 'mail-task'
  | 'my-shift'
  | 'swap'
  | 'order'
  | 'service'
  | 'team'
  | 'review'
  | 'run-pc'
  | 'assignment'
  | 'attendance'
  | 'grades'
  | 'drop'
  | 'page';

export type DirectionAssistant = 'all' | 'work' | 'clients' | 'code' | 'classes' | 'audience' | 'none';

export interface Direction {
  tabs: DirectionTab[];
  quick: DirectionQuick[];
  wallet: boolean;
  assistant: DirectionAssistant;
}

/** Що кожен напрям мав би мати (profile-types.md). Показується лише те, чий екран зареєстровано. */
export const DIRECTIONS: Record<DirectionId, Direction> = {
  personal: { tabs: ['map', 'wallet'], quick: ['write', 'moment', 'meet'], wallet: true, assistant: 'all' },
  work: { tabs: ['tasks', 'shifts'], quick: ['mail-task', 'my-shift', 'swap'], wallet: false, assistant: 'work' },
  business: {
    tabs: ['inbox', 'orders', 'services'],
    quick: ['order', 'service', 'team'],
    wallet: true,
    assistant: 'clients',
  },
  dev: { tabs: ['code'], quick: ['review', 'run-pc'], wallet: false, assistant: 'code' },
  study: { tabs: ['classes', 'schedule'], quick: ['assignment', 'attendance', 'grades'], wallet: false, assistant: 'classes' },
  author: { tabs: ['moments', 'pages', 'wallet'], quick: ['moment', 'drop', 'page'], wallet: true, assistant: 'audience' },
  quiet: { tabs: [], quick: ['write'], wallet: false, assistant: 'none' },
};

/**
 * Вкладки з екраном у збірці: мапа й гаманець — рідні; задачі, зміни, вхідні, замовлення,
 * послуги й класи — веб-екрани гілок напрямів (їх малює DirectionTabHost). Розкладу, коду,
 * моментів і сторінок як вкладок ще немає, тому їх не видно.
 */
const tabScreens = new Set<DirectionTab>(['map', 'wallet', 'tasks', 'shifts', 'inbox', 'orders', 'services', 'classes']);

/** Вкладки, що живуть у веб-хості, а не в рідній смузі. */
export const WEB_SLOT_TABS: readonly DirectionTab[] = ['tasks', 'shifts', 'inbox', 'orders', 'services', 'classes'];
/** Швидкі дії з реальним входом: послуги й команда ведуть на екран бізнес-профілю. */
const quickScreens = new Set<DirectionQuick>(['service', 'team']);

export function registerDirectionScreens(screens: { tabs?: DirectionTab[]; quick?: DirectionQuick[] }): void {
  for (const t of screens.tabs ?? []) tabScreens.add(t);
  for (const q of screens.quick ?? []) quickScreens.add(q);
}

export const hasTabScreen = (t: DirectionTab): boolean => tabScreens.has(t);
export const hasQuickScreen = (q: DirectionQuick): boolean => quickScreens.has(q);
