/**
 * Комерційна модель Nightpost — єдине джерело правди для сайту.
 *
 * Ліцензія безстрокова, на пристрій, з вікном оновлень. Безпека не коштує
 * грошей: розмова з шифруванням, мапа, навігація, тривоги, сховище й голос
 * зашиті у вільний рівень назавжди. Платне — масштаб і зручність.
 *
 * Дзеркало — телефон, `core-license/…/LicenseModel.kt` на гілці
 * `integration/beta`: саме звідти йде збірка, яку люди встановлюють. Тут
 * лишається лише те, що ключ там справді відкриває; `live: false` — право
 * є в рівні, але за ним ще немає воріт у застосунку, і продавати його не
 * можна.
 */

export type PlanId = 'free' | 'personal' | 'crew' | 'unit';

/** На чому право працює сьогодні. */
export type FeatureSurface = 'both' | 'phone' | 'pc';

export interface PlanFeature {
  /** Ключ права; збігається з LicenseFeature у телефоні. */
  key: string;
  /** Право справді працює в продукті. Хибне — дорожня карта, не привід платити. */
  live: boolean;
}

export interface Plan {
  id: PlanId;
  /** Скільки комп’ютерів можна активувати одним ключем; null — за домовленістю. */
  devices: number | null;
  /** Роки оновлень у ключі; null — за домовленістю. */
  updateYears: number | null;
  features: PlanFeature[];
}

export const FEATURE_ORDER = [
  'core.chat',
  'core.map',
  'core.nav',
  'core.alerts',
  'core.vault',
  'core.voice',
  'bridge.pair',
  'map.terrain3d',
  'gnss.blackbox',
  'export.reports',
] as const;

export type FeatureKey = (typeof FEATURE_ORDER)[number];

export const FEATURE_SURFACE: Record<FeatureKey, FeatureSurface> = {
  'core.chat': 'both',
  'core.map': 'both',
  'core.nav': 'both',
  'core.alerts': 'both',
  'core.vault': 'both',
  'core.voice': 'both',
  'bridge.pair': 'both',
  'map.terrain3d': 'both',
  'gnss.blackbox': 'phone',
  'export.reports': 'both',
};

export const surfaceOf = (key: FeatureKey): FeatureSurface => FEATURE_SURFACE[key];

const FREE_FEATURES: PlanFeature[] = [
  { key: 'core.chat', live: true },
  { key: 'core.map', live: true },
  { key: 'core.nav', live: true },
  { key: 'core.alerts', live: true },
  { key: 'core.vault', live: true },
  { key: 'core.voice', live: true },
];

const PAID_FEATURES: PlanFeature[] = [
  ...FREE_FEATURES,
  { key: 'bridge.pair', live: true },
  { key: 'map.terrain3d', live: true },
  // Право є в рівні, але жоден екран телефона його не питає
  // (PaidGateWiringTest: «GnssBlackbox … stays ungated»).
  { key: 'gnss.blackbox', live: false },
  { key: 'export.reports', live: true },
];

// Екіпаж і підрозділ поки не відкривають нічого понад особистий: те, що їх
// відрізняє, ще не існує — і тут не малюється.
export const PLANS: Plan[] = [
  { id: 'free', devices: 1, updateYears: null, features: FREE_FEATURES },
  { id: 'personal', devices: 2, updateYears: 1, features: PAID_FEATURES },
  { id: 'crew', devices: 5, updateYears: 2, features: PAID_FEATURES },
  { id: 'unit', devices: null, updateYears: null, features: PAID_FEATURES },
];

export const planById = (id: PlanId): Plan => PLANS.find((p) => p.id === id)!;

/** Платний ярус без ключа — кожному. 60, не 14: рішення власника 01.09;
 *  телефон уже так рахує (LicenseModel.TRIAL_DAYS на integration/beta). */
export const TRIAL_DAYS = 60;

/** Скільки днів ліцензія переживає повну відсутність мережі. */
export const OFFLINE_GRACE_DAYS = 30;

export function planHas(plan: Plan, key: string): PlanFeature | undefined {
  return plan.features.find((f) => f.key === key);
}
