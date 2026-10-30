/**
 * Набір інструментів профілю (11-spaces §7, картка msg-x 02): середні вкладки
 * нижньої смуги, швидкі дії, правило сповіщень, межі помічника, гаманець.
 * Розмови стоять першими, «Я» — останнім; набір їх не містить і не міняє.
 * Кольорів тут немає: акцент — окреме налаштування.
 */
export const KIT_TABS = [
  'day',
  'map',
  'wallet',
  'tasks',
  'shifts',
  'classes',
  'schedule',
  'inbox',
  'orders',
  'bookings',
  'repos',
] as const;
export type KitTab = (typeof KIT_TABS)[number];

export const KIT_QUICK = [
  'plan',
  'money',
  'place',
  'assignment',
  'attendance',
  'grades',
  'announce',
  'task',
  'shift',
  'approve',
  'reply',
  'order',
  'pr',
  'deploy',
] as const;
export type KitQuick = (typeof KIT_QUICK)[number];

/** `hours` — години профілю (`Profile.quiet`), окремих годин набір не тримає. */
export const KIT_NOTIFY = ['circle', 'hours', 'shift', 'oncall'] as const;
export type KitNotify = (typeof KIT_NOTIFY)[number];

export const KIT_ASSISTANT = ['all', 'spaces', 'none'] as const;
export type KitAssistant = (typeof KIT_ASSISTANT)[number];

export const KIT_WALLETS = ['personal', 'work', 'business'] as const;
export type KitWallet = (typeof KIT_WALLETS)[number];

export const KIT_MAX_TABS = 3;
/** Бізнес: Вхідні · Замовлення · Записи · Зміни (рішення лідера 22.10). */
export const KIT_MAX_TABS_BUSINESS = 4;
export const KIT_MAX_QUICK = 4;

export interface ProfileKit {
  tabs: KitTab[];
  quick: KitQuick[];
  notify: KitNotify;
  assistant: KitAssistant;
  wallet: KitWallet | null;
}
